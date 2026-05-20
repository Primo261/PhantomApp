package com.phantom.app.license

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

sealed class ActivationResult {
    object Success : ActivationResult()
    data class Failure(val reason: String, val userMessage: String) : ActivationResult()
}

object LicenseGuard {

    private const val TAG = LicenseConfig.LOG_TAG
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Synchronous validity check called from the UI thread (gate in MainActivity).
     * Pure local checks + opportunistic async re-verify if grace period exceeded.
     */
    fun isValid(context: Context): Boolean {
        val stored = try {
            LicenseStorage.getStoredLicense(context)
        } catch (e: Exception) {
            Log.e(TAG, "isValid: storage error: ${e.message}")
            return false
        }
        if (stored == null) {
            Log.d(TAG, "isValid: no stored license -> false")
            return false
        }

        val now = System.currentTimeMillis()
        val nowSec = now / 1000L
        if (stored.payload.expires_at <= nowSec) {
            Log.w(TAG, "isValid: license expired (expires_at=${stored.payload.expires_at} now=$nowSec) -> false")
            return false
        }

        val signatureBytes = try {
            Ed25519Verifier.hexDecode(stored.signatureHex)
        } catch (e: Exception) {
            Log.e(TAG, "isValid: bad signature hex: ${e.message}")
            return false
        }
        val licenseBytes = LicenseParser.parse(stored.licenseKey)?.rawBytes
        if (licenseBytes == null) {
            Log.e(TAG, "isValid: stored license key has invalid format")
            return false
        }
        val message = buildSignedMessage(stored.payload, licenseBytes)
        val sigOk = Ed25519Verifier.verify(
            LicenseConfig.PUBLIC_KEY_HEX, message, signatureBytes
        )
        if (!sigOk) {
            Log.e(TAG, "isValid: Ed25519 signature mismatch -> false")
            return false
        }

        val graceMs = TimeUnit.DAYS.toMillis(LicenseConfig.OFFLINE_GRACE_PERIOD_DAYS.toLong())
        val withinGrace = stored.lastVerifiedAt > 0L && (now - stored.lastVerifiedAt) <= graceMs
        Log.d(TAG, "isValid: signature OK, withinGrace=$withinGrace (lastVerified=${stored.lastVerifiedAt})")

        if (!withinGrace) {
            // Fire-and-forget background re-verify. Result will be applied on
            // next launch via clearLicense() if invalid.
            bgScope.launch {
                runCatching { backgroundReverify(context, stored) }
                    .onFailure { Log.w(TAG, "background reverify error: ${it.message}") }
            }
        }
        return true
    }

    private suspend fun backgroundReverify(context: Context, stored: StoredLicense) {
        Log.d(TAG, "backgroundReverify: starting for ${stored.licenseKey.take(16)}…")
        val req = VerifyRequest(
            licenseKey = stored.licenseKey,
            deviceId = stored.deviceId,
            deviceInfo = deviceInfo()
        )
        val res = LicenseApi.verifyOnline(req)
        if (res.isFailure) {
            Log.w(TAG, "backgroundReverify: network failure; keeping license active within grace")
            return
        }
        val resp = res.getOrThrow()
        if (resp.valid) {
            Log.d(TAG, "backgroundReverify: server says valid; refreshing lastVerifiedAt")
            LicenseStorage.updateLastVerified(context)
        } else {
            Log.w(TAG, "backgroundReverify: server says invalid (reason=${resp.reason}); clearing")
            LicenseStorage.clearLicense(context)
        }
    }

    suspend fun activate(context: Context, licenseKey: String): ActivationResult {
        val normalized = LicenseParser.normalize(licenseKey)
        Log.d(TAG, "activate: normalized='$normalized'")
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

        LicenseStorage.saveActivation(context, normalized, payload, signatureHex)
        Log.d(TAG, "activate: SUCCESS — saved activation")
        return ActivationResult.Success
    }

    private fun buildSignedMessage(payload: LicensePayload, rawLicenseBytes: ByteArray): ByteArray {
        val canonical = payload.toCanonicalJson().toByteArray(Charsets.UTF_8)
        val out = ByteArray(canonical.size + rawLicenseBytes.size)
        System.arraycopy(canonical, 0, out, 0, canonical.size)
        System.arraycopy(rawLicenseBytes, 0, out, canonical.size, rawLicenseBytes.size)
        return out
    }

    private fun deviceInfo(): Map<String, String> = mapOf(
        "manufacturer" to (Build.MANUFACTURER ?: ""),
        "model" to (Build.MODEL ?: ""),
        "brand" to (Build.BRAND ?: ""),
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "release" to (Build.VERSION.RELEASE ?: "")
    )
}
