package com.phantom.app.license

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import com.phantom.app.util.Slog
import androidx.security.crypto.MasterKey
import java.util.UUID

data class StoredLicense(
    val licenseKey: String,
    val deviceId: String,
    val payload: LicensePayload,
    val signatureHex: String,
    val lastVerifiedAt: Long
)

object LicenseStorage {

    private const val TAG = LicenseConfig.LOG_TAG
    private const val FILE_NAME = "phantom_license_secure"

    private const val KEY_LICENSE = "LICENSE_KEY"
    private const val KEY_DEVICE_ID = "DEVICE_ID"
    private const val KEY_PAYLOAD_JSON = "PAYLOAD_JSON"
    private const val KEY_SIGNATURE_HEX = "SIGNATURE_HEX"
    private const val KEY_LAST_VERIFIED = "LAST_VERIFIED_AT"
    private const val KEY_LAST_KNOWN_TIME = "LAST_KNOWN_TIME"

    @Volatile private var prefs: SharedPreferences? = null
    private val writeLock = Any()

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val ctx = context.applicationContext
            val sp = try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Fail-fast: if AndroidKeyStore is broken we MUST NOT silently
                // fall back to plain SharedPreferences — that would expose the
                // license payload + signature + deviceId in clear on disk and
                // (combined with allowBackup=true) let an attacker exfiltrate
                // them. The caller (LicenseGuard / Activity) catches this and
                // surfaces the error to the user.
                Log.e(TAG, "EncryptedSharedPreferences create failed - SECURITY ERROR", e)
                throw IllegalStateException(
                    "Cannot initialize secure storage. Device crypto broken.",
                    e
                )
            }
            prefs = sp
            return sp
        }
    }

    fun getOrCreateDeviceId(context: Context): String {
        val sp = prefs(context)
        val existing = sp.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            Slog.d(TAG, "getOrCreateDeviceId: existing=$existing")
            return existing
        }
        val newId = UUID.randomUUID().toString()
        synchronized(writeLock) {
            sp.edit().putString(KEY_DEVICE_ID, newId).apply()
        }
        Slog.d(TAG, "getOrCreateDeviceId: created=$newId")
        return newId
    }

    fun saveActivation(
        context: Context,
        licenseKey: String,
        payload: LicensePayload,
        signatureHex: String
    ) {
        Slog.d(TAG, "saveActivation: license=${licenseKey.take(16)}… expires=${payload.expires_at}")
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            prefs(context).edit()
                .putString(KEY_LICENSE, licenseKey)
                .putString(KEY_PAYLOAD_JSON, payload.toCanonicalJson())
                .putString(KEY_SIGNATURE_HEX, signatureHex)
                .putLong(KEY_LAST_VERIFIED, now)
                .putLong(KEY_LAST_KNOWN_TIME, now)
                .apply()
        }
    }

    fun updateLastVerified(context: Context, ts: Long = System.currentTimeMillis()) {
        synchronized(writeLock) {
            prefs(context).edit()
                .putLong(KEY_LAST_VERIFIED, ts)
                .putLong(KEY_LAST_KNOWN_TIME, ts)
                .apply()
        }
    }

    /**
     * Last `System.currentTimeMillis()` we observed and trusted (server-validated
     * activation or successful re-verify). Used to detect clock rollback: if the
     * current wall-clock is significantly earlier than this value, the user has
     * almost certainly turned off NTP and rolled their device clock back to keep
     * an expired license alive.
     */
    fun getLastKnownTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_KNOWN_TIME, 0L)

    fun clearLicense(context: Context) {
        Log.w(TAG, "clearLicense: wiping stored activation")
        synchronized(writeLock) {
            val sp = prefs(context)
            val deviceId = sp.getString(KEY_DEVICE_ID, null)
            val editor = sp.edit().clear()
            // Preserve deviceId across re-activations (single transactional
            // commit so the prefs file is never observable in a half-cleared
            // state by a concurrent reader).
            if (deviceId != null) {
                editor.putString(KEY_DEVICE_ID, deviceId)
            }
            editor.apply()
        }
    }

    fun getStoredLicense(context: Context): StoredLicense? {
        val sp = prefs(context)
        val key = sp.getString(KEY_LICENSE, null) ?: return null
        val deviceId = sp.getString(KEY_DEVICE_ID, null) ?: return null
        val payloadJson = sp.getString(KEY_PAYLOAD_JSON, null) ?: return null
        val signatureHex = sp.getString(KEY_SIGNATURE_HEX, null) ?: return null
        val lastVerified = sp.getLong(KEY_LAST_VERIFIED, 0L)
        val payload = LicensePayload.fromJsonOrNull(payloadJson) ?: run {
            Log.e(TAG, "getStoredLicense: payload JSON parse failed; treating as no license")
            return null
        }
        return StoredLicense(key, deviceId, payload, signatureHex, lastVerified)
    }
}
