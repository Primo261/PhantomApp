package com.phantom.app.news

import android.util.Log
import com.phantom.app.license.LicenseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NewsItem(
    val id: String,
    val message: String,
    val type: String,        // info | warning | success | error
    val link: String?,       // nullable
    val dismissible: Boolean
)

object NewsApi {

    private const val TAG = LicenseConfig.LOG_TAG
    private const val ENDPOINT = "https://admin.phantomapp.fr/api/news"
    private const val TIMEOUT_MS = 3_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Returns the active news item, or null if there's none / fetch failed.
     * Never throws.
     */
    suspend fun fetch(): NewsItem? = withContext(Dispatchers.IO) {
        try {
            withTimeout(TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "PhantomApp-News/1.0")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "NewsApi: HTTP ${resp.code}")
                        return@withTimeout null
                    }
                    val body = resp.body?.string().orEmpty()
                    parse(body)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "NewsApi: timeout")
            null
        } catch (e: Exception) {
            Log.w(TAG, "NewsApi: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun parse(body: String): NewsItem? {
        if (body.isBlank()) return null
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        if (!obj.has("id") || !obj.has("message")) return null
        val id = obj.optString("id", "")
        val message = obj.optString("message", "")
        if (id.isBlank() || message.isBlank()) return null
        val type = obj.optString("type", "info").ifBlank { "info" }
        val link = if (obj.has("link") && !obj.isNull("link")) obj.optString("link") else null
        val dismissible = obj.optBoolean("dismissible", true)
        return NewsItem(id, message, type, link, dismissible)
    }
}
