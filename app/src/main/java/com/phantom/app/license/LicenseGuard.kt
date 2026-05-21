package com.phantom.app.license

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.phantom.app.util.Slog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

sealed class ActivationResult {
    object Success : ActivationResult()
    data class Failure(val reason: String, val userMessage: String) : ActivationResult()
}

object LicenseGuard {

    private const val TAG = LicenseConfig.LOG_TAG

    /**
     * License gate called from MainActivity.onCreate.
     *
     * Order of checks:
     *   1. Local: stored license exists, signature valid, expires_at > now.
     *      If any fails → return false immediately.
     *   2. If internet is available → mandatory online re-verify with a 5s
     *      timeout. Server's verdict wins:
     *        - valid=false → clear storage, return false.
     *        - valid=true  → refresh lastVerifiedAt, return true.
     *        - timeout / network error mid-call → fall through to step 3.
     *   3. Offline (or step-2 fell through) → return true only if
     *      lastVerifiedAt is within OFFLINE_GRACE_PERIOD_MS (1h). Beyond that,
     *      we refuse the boot and force re-activation.
     */
    suspend fun isValid(context: Context): Boolean {
        if (!isValidLocalOnly(context)) return false

        // At this point local checks passed (signature + expiry + clock).
        // Re-read storage for the online step — cheap, prefs are cached.
        val stored = try {
            LicenseStorage.getStoredLicense(context)
        } catch (e: Exception) {
            Log.e(TAG, "isValid: storage error during online step: ${e.message}")
            return false
        } ?: return false

        val now = System.currentTimeMillis()
        if (hasInternet(context)) {
            val verdict = onlineVerifyWithTimeout(stored)
            when (verdict) {
                OnlineVerdict.VALID -> {
                    LicenseStorage.updateLastVerified(context)
                    Slog.d(TAG, "isValid: online OK -> true")
                    return true
                }
                OnlineVerdict.INVALID -> {
                    Log.w(TAG, "isValid: online INVALID, clearing storage -> false")
                    LicenseStorage.clearLicense(context)
                    return false
                }
                OnlineVerdict.UNREACHABLE -> {
                    Log.w(TAG, "isValid: online unreachable (timeout/error), falling back to grace")
                }
            }
        } else {
            Slog.d(TAG, "isValid: no internet, falling back to grace")
        }

        val withinGrace = stored.lastVerifiedAt > 0L &&
            (now - stored.lastVerifiedAt) <= LicenseConfig.OFFLINE_GRACE_PERIOD_MS
        Slog.d(
            TAG,
            "isValid: graceCheck withinGrace=$withinGrace " +
                "(lastVerified=${stored.lastVerifiedAt}, age=${now - stored.lastVerifiedAt}ms)"
        )
        return withinGrace
    }

    /**
     * Pure-local boot gate: stored license exists, signature is valid,
     * payload is not expired, and the device clock has not been rolled back
     * relative to the last known-good time. No network, no coroutine
     * suspension — safe to call from `Activity.onCreate` without blocking.
     *
     * Returns false on any failure; the caller routes to ActivationActivity.
     * The online reverify is run separately, in the background, by the
     * caller (see MainActivity).
     */
    fun isValidLocalOnly(context: Context): Boolean {
        val stored = try {
            LicenseStorage.getStoredLicense(context)
        } catch (e: Exception) {
            Log.e(TAG, "isValidLocalOnly: storage error: ${e.message}")
            return false
        }
        if (stored == null) {
            Slog.d(TAG, "isValidLocalOnly: no stored license -> false")
            return false
        }

        // Clock-rollback detection. Allow 60s of tolerance for legitimate NTP
        // re-syncs; beyond that, assume the user rolled the clock back to
        // extend an expired license.
        val now = System.currentTimeMillis()
        val lastKnown = try {
            LicenseStorage.getLastKnownTime(context)
        } catch (e: Exception) {
            0L
        }
        if (lastKnown > 0L && now < lastKnown - 60_000L) {
            Log.w(TAG, "isValidLocalOnly: clock rollback detected (now=$now, lastKnown=$lastKnown)")
            return false
        }

        val nowSec = now / 1000L
        if (stored.payload.expires_at <= nowSec) {
            Log.w(TAG, "isValidLocalOnly: license expired (expires_at=${stored.payload.expires_at} now=$nowSec)")
            return false
        }

        if (!verifyLocalSignature(stored)) {
            Log.e(TAG, "isValidLocalOnly: local Ed25519 mismatch -> false")
            return false
        }

        return true
    }

    suspend fun activate(context: Context, licenseKey: String): ActivationResult {
        val normalized = LicenseParser.normalize(licenseKey)
        Slog.d(TAG, "activate: normalized='$normalized'")
        if (normalized == null || !LicenseParser.isValidFormat(normalized)) {
            return ActivationResult.Failure(
                "format_invalid",
                "Format de license invalide"
            )
        }
        val parsed = LicenseParser.parse(normalized)
            ?: return ActivationResult.Failure(
                "format_invalid",
                "Format de license invalide"
            )

        val deviceId = LicenseStorage.getOrCreateDeviceId(context)
        val req = VerifyRequest(
            licenseKey = normalized,
            deviceId = deviceId,
            deviceInfo = deviceInfo()
        )

        val res = LicenseApi.verifyOnline(req)
        if (res.isFailure) {
            val err = res.exceptionOrNull()
            Log.w(TAG, "activate: network failure: ${err?.javaClass?.simpleName}: ${err?.message}")
            return ActivationResult.Failure(
                "network",
                "Pas de connexion. Vérifie ton wifi/4G."
            )
        }

        val resp = res.getOrThrow()
        if (!resp.valid) {
            val reason = resp.reason ?: "unknown"
            val msg = when (reason) {
                "not_found" -> "License inconnue. Vérifie ta saisie."
                "signature_invalid" -> "License corrompue. Re-demande-en une à l'admin."
                "expired" -> "License expirée. Contacte l'admin pour renouveler."
                "revoked" -> "License révoquée. Contacte l'admin."
                "device_limit_exceeded" -> "Trop de devices activés. Contacte l'admin."
                else -> "Activation refusée: $reason"
            }
            Log.w(TAG, "activate: server rejected reason=$reason")
            return ActivationResult.Failure(reason, msg)
        }

        val payload = resp.payload
        val signatureHex = resp.signature_hex
        if (payload == null || signatureHex == null) {
            Log.e(TAG, "activate: server said valid but payload/signature missing")
            return ActivationResult.Failure(
                "signature_invalid",
                "Réponse serveur incomplète. Réessaye."
            )
        }

        val signatureBytes = try {
            Ed25519Verifier.hexDecode(signatureHex)
        } catch (e: Exception) {
            Log.e(TAG, "activate: bad signature hex from server: ${e.message}")
            return ActivationResult.Failure(
                "signature_invalid",
                "Erreur. Réessaye."
            )
        }
        val message = buildSignedMessage(payload, parsed.rawBytes)
        Slog.d(TAG, "verifyLocal: signature_hex=$signatureHex")
        val sigOk = Ed25519Verifier.verify(
            LicenseConfig.PUBLIC_KEY_HEX, message, signatureBytes
        )
        if (!sigOk) {
            Log.e(TAG, "activate: signature sanity check FAILED")
            return ActivationResult.Failure(
                "signature_invalid",
                "Erreur. Réessaye."
            )
        }

        // Defence in depth: refuse to save an activation whose payload is
        // already expired. Without this check, the boot gate (isValid) would
        // reject on next launch and the user would see "Activation Success"
        // immediately followed by a forced re-activation prompt — terrible UX.
        val nowSec = System.currentTimeMillis() / 1000L
        if (payload.expires_at <= nowSec) {
            Log.e(TAG, "activate: payload already expired (expires_at=${payload.expires_at} now=$nowSec)")
            return ActivationResult.Failure(
                "expired",
                "Licence déjà expirée. Contacte l'admin."
            )
        }

        LicenseStorage.saveActivation(context, normalized, payload, signatureHex)
        Slog.d(TAG, "activate: SUCCESS — saved activation")
        return ActivationResult.Success
    }

    /**
     * Pure-offline signature + expiry check, no network. Used by the boot gate
     * and the periodic watchdog to short-circuit when storage is already bad.
     */
    private fun verifyLocalSignature(stored: StoredLicense): Boolean {
        val signatureBytes = try {
            Ed25519Verifier.hexDecode(stored.signatureHex)
        } catch (e: Exception) {
            Log.e(TAG, "verifyLocal: bad signature hex: ${e.message}")
            return false
        }
        val licenseBytes = LicenseParser.parse(stored.licenseKey)?.rawBytes
        if (licenseBytes == null) {
            Log.e(TAG, "verifyLocal: stored license key has invalid format")
            return false
        }
        val message = buildSignedMessage(stored.payload, licenseBytes)
        Slog.d(TAG, "verifyLocal: signature_hex=${stored.signatureHex}")
        return Ed25519Verifier.verify(
            LicenseConfig.PUBLIC_KEY_HEX, message, signatureBytes
        )
    }

    private enum class OnlineVerdict { VALID, INVALID, UNREACHABLE }

    private suspend fun onlineVerifyWithTimeout(stored: StoredLicense): OnlineVerdict {
        val req = VerifyRequest(
            licenseKey = stored.licenseKey,
            deviceId = stored.deviceId,
            deviceInfo = deviceInfo()
        )
        return try {
            withTimeout(LicenseConfig.ONLINE_VERIFY_TIMEOUT_MS) {
                val res = LicenseApi.verifyOnline(req)
                if (res.isFailure) {
                    Log.w(TAG, "onlineVerify: network failure: ${res.exceptionOrNull()?.message}")
                    OnlineVerdict.UNREACHABLE
                } else {
                    val resp = res.getOrThrow()
                    if (resp.valid) OnlineVerdict.VALID
                    else {
                        Log.w(TAG, "onlineVerify: server says invalid (reason=${resp.reason})")
                        OnlineVerdict.INVALID
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "onlineVerify: timed out after ${LicenseConfig.ONLINE_VERIFY_TIMEOUT_MS}ms")
            OnlineVerdict.UNREACHABLE
        } catch (e: Exception) {
            Log.w(TAG, "onlineVerify: unexpected error: ${e.javaClass.simpleName}: ${e.message}")
            OnlineVerdict.UNREACHABLE
        }
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal suspend fun reverifyOnce(context: Context): OnlineReverifyOutcome {
        val stored = try {
            LicenseStorage.getStoredLicense(context)
        } catch (e: Exception) {
            Log.e(TAG, "reverifyOnce: storage error: ${e.message}")
            return OnlineReverifyOutcome.NO_LICENSE
        } ?: return OnlineReverifyOutcome.NO_LICENSE
        if (!hasInternet(context)) return OnlineReverifyOutcome.UNREACHABLE
        return when (onlineVerifyWithTimeout(stored)) {
            OnlineVerdict.VALID -> {
                LicenseStorage.updateLastVerified(context)
                OnlineReverifyOutcome.VALID
            }
            OnlineVerdict.INVALID -> {
                LicenseStorage.clearLicense(context)
                OnlineReverifyOutcome.INVALID
            }
            OnlineVerdict.UNREACHABLE -> OnlineReverifyOutcome.UNREACHABLE
        }
    }

    enum class OnlineReverifyOutcome { VALID, INVALID, UNREACHABLE, NO_LICENSE }

    private fun buildSignedMessage(payload: LicensePayload, rawLicenseBytes: ByteArray): ByteArray {
        val canonicalStr = payload.toCanonicalJson()
        val canonical = canonicalStr.toByteArray(Charsets.UTF_8)
        val out = ByteArray(canonical.size + rawLicenseBytes.size)
        System.arraycopy(canonical, 0, out, 0, canonical.size)
        System.arraycopy(rawLicenseBytes, 0, out, canonical.size, rawLicenseBytes.size)
        Slog.d(TAG, "verifyLocal: canonical_json=$canonicalStr")
        Slog.d(TAG, "verifyLocal: message_bytes=${out.size} bytes (canonical=${canonical.size} + key=${rawLicenseBytes.size})")
        Slog.d(TAG, "verifyLocal: rawLicenseKeyBytes=${rawLicenseBytes.toHex()}")
        return out
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun deviceInfo(): Map<String, String> = mapOf(
        // Legacy fields kept for server back-compat. These are HOST-process
        // values (the engine's spoofing layer only covers slot processes),
        // so they leak the real device identity — see SEC-007 in the audit.
        // The "fingerprint" entry below is the privacy-preserving replacement
        // that newer server logic should rely on.
        "manufacturer" to (Build.MANUFACTURER ?: ""),
        "model" to (Build.MODEL ?: ""),
        "brand" to (Build.BRAND ?: ""),
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "release" to (Build.VERSION.RELEASE ?: ""),
        "fingerprint" to deviceFingerprint()
    )

    /**
     * Privacy-preserving device fingerprint: SHA-256 of a composite of the
     * host's Build identifiers, truncated to 16 hex chars. Stable for a given
     * device + ROM combination, but doesn't disclose the actual model name
     * to the server (or to anyone who intercepts the request body).
     */
    private fun deviceFingerprint(): String {
        val raw = buildString {
            append(Build.MANUFACTURER ?: "")
            append('|')
            append(Build.MODEL ?: "")
            append('|')
            append(Build.BRAND ?: "")
            append('|')
            append(Build.BOARD ?: "")
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            buildString(32) {
                for (i in 0 until 8) {
                    append(String.format("%02x", digest[i].toInt() and 0xFF))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deviceFingerprint hash failed: ${e.message}")
            ""
        }
    }
}
