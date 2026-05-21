package com.phantom.app.license

import android.util.Log
import com.phantom.app.util.Slog

data class ParsedLicense(val rawBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedLicense) return false
        return rawBytes.contentEquals(other.rawBytes)
    }
    override fun hashCode(): Int = rawBytes.contentHashCode()
}

object LicenseParser {

    private const val TAG = LicenseConfig.LOG_TAG

    // Alphabet excludes I, L, O, U per Crockford. Accepts 0-9 and these letters.
    private val FORMAT_REGEX =
        Regex("^PHANTOM-[0-9A-HJKMNPQRSTVWXYZ]{5}-[0-9A-HJKMNPQRSTVWXYZ]{5}-[0-9A-HJKMNPQRSTVWXYZ]{5}-[0-9A-HJKMNPQRSTVWXYZ]{5}$")

    fun isValidFormat(key: String): Boolean {
        val normalized = normalize(key) ?: return false
        val ok = FORMAT_REGEX.matches(normalized)
        Slog.d(TAG, "isValidFormat($normalized) = $ok")
        return ok
    }

    fun parse(licenseKey: String): ParsedLicense? {
        val normalized = normalize(licenseKey)
        if (normalized == null || !FORMAT_REGEX.matches(normalized)) {
            Log.w(TAG, "parse: invalid format for input '$licenseKey'")
            return null
        }
        val stripped = normalized
            .removePrefix(LicenseConfig.LICENSE_PREFIX)
            .replace("-", "")
        if (stripped.length != 20) {
            Log.w(TAG, "parse: stripped length != 20 (got ${stripped.length})")
            return null
        }
        return try {
            val bytes = CrockfordBase32.decode(stripped)
            Slog.d(TAG, "parse: decoded ${bytes.size} bytes from '$normalized'")
            ParsedLicense(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "parse: decode failed: ${e.message}")
            null
        }
    }

    /**
     * Normalize: uppercase, trim, accept lowercase/mixed-case input.
     * Returns null if input is null/blank.
     */
    fun normalize(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return key.trim().uppercase()
    }
}
