package com.phantom.app.license

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
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
    private const val FALLBACK_FILE_NAME = "phantom_license_plain"

    private const val KEY_LICENSE = "LICENSE_KEY"
    private const val KEY_DEVICE_ID = "DEVICE_ID"
    private const val KEY_PAYLOAD_JSON = "PAYLOAD_JSON"
    private const val KEY_SIGNATURE_HEX = "SIGNATURE_HEX"
    private const val KEY_LAST_VERIFIED = "LAST_VERIFIED_AT"

    @Volatile private var prefs: SharedPreferences? = null

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
                // androidx.security can fail on some weird OEM ROMs / corrupted
                // keystore states. Fall back to plain prefs so we don't brick the
                // app — the license payload is still Ed25519-signed so a tamperer
                // can't forge a valid record anyway.
                Log.e(TAG, "EncryptedSharedPreferences init failed, falling back: ${e.message}")
                ctx.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
            }
            prefs = sp
            return sp
        }
    }

    fun getOrCreateDeviceId(context: Context): String {
        val sp = prefs(context)
        val existing = sp.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            Log.d(TAG, "getOrCreateDeviceId: existing=$existing")
            return existing
        }
        val newId = UUID.randomUUID().toString()
        sp.edit().putString(KEY_DEVICE_ID, newId).apply()
        Log.d(TAG, "getOrCreateDeviceId: created=$newId")
        return newId
    }

    fun saveActivation(
        context: Context,
        licenseKey: String,
        payload: LicensePayload,
        signatureHex: String
    ) {
        Log.d(TAG, "saveActivation: license=${licenseKey.take(16)}… expires=${payload.expires_at}")
        prefs(context).edit()
            .putString(KEY_LICENSE, licenseKey)
            .putString(KEY_PAYLOAD_JSON, payload.toCanonicalJson())
            .putString(KEY_SIGNATURE_HEX, signatureHex)
            .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
            .apply()
    }

    fun updateLastVerified(context: Context, ts: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_VERIFIED, ts).apply()
    }

    fun clearLicense(context: Context) {
        Log.w(TAG, "clearLicense: wiping stored activation")
        val sp = prefs(context)
        val deviceId = sp.getString(KEY_DEVICE_ID, null)
        sp.edit().clear().apply()
        // Preserve deviceId across re-activations.
        if (deviceId != null) {
            sp.edit().putString(KEY_DEVICE_ID, deviceId).apply()
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
