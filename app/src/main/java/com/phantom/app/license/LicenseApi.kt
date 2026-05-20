package com.phantom.app.license

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VerifyRequest(
    val licenseKey: String,
    val deviceId: String,
    val deviceInfo: Map<String, String>? = null
)

data class VerifyResponse(
    val valid: Boolean,
    val reason: String? = null,
    val label: String? = null,
    val expires_at: Long? = null,
    val device_count: Int? = null,
    val device_limit: Int? = null,
    val payload: LicensePayload? = null,
    val signature_hex: String? = null
)

object LicenseApi {

    private const val TAG = LicenseConfig.LOG_TAG
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun verifyOnline(req: VerifyRequest): Result<VerifyResponse> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "verifyOnline: POST ${LicenseConfig.VERIFY_API_URL} key=${req.licenseKey.take(16)}… device=${req.deviceId}")
                val body = JSONObject().apply {
                    put("license_key", req.licenseKey)
                    put("device_id", req.deviceId)
                    req.deviceInfo?.let { info ->
                        put("device_info", JSONObject(info as Map<*, *>))
                    }
                }.toString()

                val request = Request.Builder()
                    .url(LicenseConfig.VERIFY_API_URL)
                    .post(body.toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .header("User-Agent", "PhantomApp-License/1.0")
                    .build()

                client.newCall(request).execute().use { resp ->
                    val code = resp.code
                    val text = resp.body?.string().orEmpty()
                    Log.d(TAG, "verifyOnline: HTTP $code (${text.length} bytes)")
                    if (!resp.isSuccessful && code !in 400..499) {
                        return@withContext Result.failure(
                            RuntimeException("HTTP $code: ${text.take(200)}")
                        )
                    }
                    val parsed = parseResponse(text)
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyOnline error: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        }

    private fun parseResponse(json: String): VerifyResponse {
        val obj = JSONObject(json)
        val valid = obj.optBoolean("valid", false)
        val reason = if (obj.has("reason") && !obj.isNull("reason")) obj.optString("reason") else null
        val label = if (obj.has("label") && !obj.isNull("label")) obj.optString("label") else null
        val expiresAt = if (obj.has("expires_at") && !obj.isNull("expires_at")) {
            obj.getLong("expires_at")
        } else null
        val deviceCount = if (obj.has("device_count") && !obj.isNull("device_count")) {
            obj.getInt("device_count")
        } else null
        val deviceLimit = if (obj.has("device_limit") && !obj.isNull("device_limit")) {
            obj.getInt("device_limit")
        } else null

        var payload: LicensePayload? = null
        if (obj.has("payload") && !obj.isNull("payload")) {
            val p = obj.getJSONObject("payload")
            payload = LicensePayload.fromJsonOrNull(p.toString())
        }
        val signatureHex = if (obj.has("signature_hex") && !obj.isNull("signature_hex")) {
            obj.getString("signature_hex")
        } else if (obj.has("signature") && !obj.isNull("signature")) {
            obj.getString("signature")
        } else null

        return VerifyResponse(
            valid = valid,
            reason = reason,
            label = label,
            expires_at = expiresAt,
            device_count = deviceCount,
            device_limit = deviceLimit,
            payload = payload,
            signature_hex = signatureHex
        )
    }
}
