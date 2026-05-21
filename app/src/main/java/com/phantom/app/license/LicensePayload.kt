package com.phantom.app.license

import android.util.Log
import com.phantom.app.util.Slog
import org.json.JSONObject

data class LicensePayload(
    val license_id: String,
    val email: String,
    val issued_at: Long,
    val expires_at: Long,
    val label: String?
) {
    /**
     * Canonical JSON used for Ed25519 verification.
     *
     * Must reproduce byte-for-byte what the server's sign.ts canonicalizePayload()
     * produced at sign time. That function builds the object in this exact order:
     *
     *   { license_id, email, issued_at, expires_at, label }
     *
     * and serializes with JSON.stringify, which preserves insertion order and
     * drops keys whose value is undefined. So when label was absent at sign time
     * (legacy pre-label licenses), the canonical bytes contain no "label" key.
     */
    fun toCanonicalJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"license_id\":").append(jsonString(license_id)).append(',')
        sb.append("\"email\":").append(jsonString(email)).append(',')
        sb.append("\"issued_at\":").append(issued_at).append(',')
        sb.append("\"expires_at\":").append(expires_at)
        if (label != null) {
            sb.append(',').append("\"label\":").append(jsonString(label))
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        private const val TAG = LicenseConfig.LOG_TAG

        fun fromJson(json: String): LicensePayload {
            Slog.d(TAG, "LicensePayload.fromJson: parsing ${json.length} chars")
            val obj = JSONObject(json)
            val labelValue = if (obj.has("label") && !obj.isNull("label")) {
                obj.getString("label")
            } else null
            return LicensePayload(
                license_id = obj.getString("license_id"),
                email = obj.getString("email"),
                issued_at = obj.getLong("issued_at"),
                expires_at = obj.getLong("expires_at"),
                label = labelValue
            )
        }

        fun fromJsonOrNull(json: String): LicensePayload? = try {
            fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "fromJson failed: ${e.message}")
            null
        }

        private fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (c.code < 0x20) {
                            sb.append(String.format("\\u%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
