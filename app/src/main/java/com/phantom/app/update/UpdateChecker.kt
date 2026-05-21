package com.phantom.app.update

import android.content.Context
import android.os.Build
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

sealed class UpdateState {
    object UpToDate : UpdateState()
    data class Optional(
        val updateUrl: String,
        val latestVersion: String,
        val releaseNotes: String
    ) : UpdateState()
    data class Force(
        val updateUrl: String,
        val latestVersion: String,
        val releaseNotes: String
    ) : UpdateState()
}

object UpdateChecker {

    private const val TAG = LicenseConfig.LOG_TAG
    private const val ENDPOINT = "https://admin.phantomapp.fr/api/version"
    private const val TIMEOUT_MS = 5_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun check(context: Context): UpdateState = withContext(Dispatchers.IO) {
        val currentCode = currentVersionCode(context)
        if (currentCode <= 0L) {
            Log.w(TAG, "UpdateChecker: cannot read current versionCode, treating as UpToDate")
            return@withContext UpdateState.UpToDate
        }
        try {
            withTimeout(TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "PhantomApp-Update/1.0")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "UpdateChecker: HTTP ${resp.code}; treating as UpToDate")
                        return@withTimeout UpdateState.UpToDate
                    }
                    val body = resp.body?.string().orEmpty()
                    parse(body, currentCode)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "UpdateChecker: timeout after ${TIMEOUT_MS}ms; treating as UpToDate")
            UpdateState.UpToDate
        } catch (e: Exception) {
            Log.w(TAG, "UpdateChecker: ${e.javaClass.simpleName}: ${e.message}; treating as UpToDate")
            UpdateState.UpToDate
        }
    }

    private fun parse(body: String, currentCode: Long): UpdateState {
        if (body.isBlank()) return UpdateState.UpToDate
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "UpdateChecker: malformed JSON: ${e.message}")
            return UpdateState.UpToDate
        }
        val latestCode = obj.optLong("latest_version_code", 0L)
        val minRequired = obj.optLong("min_required_version_code", 0L)
        val latestVersion = obj.optString("latest_version", "")
        val updateUrl = obj.optString("update_url", "")
        val releaseNotes = obj.optString("release_notes", "")
        Log.d(
            TAG,
            "UpdateChecker: current=$currentCode latest=$latestCode minRequired=$minRequired " +
                "latestName=$latestVersion url=$updateUrl"
        )
        if (latestCode <= 0L || updateUrl.isBlank()) {
            return UpdateState.UpToDate
        }
        return when {
            currentCode < minRequired -> UpdateState.Force(updateUrl, latestVersion, releaseNotes)
            currentCode < latestCode -> UpdateState.Optional(updateUrl, latestVersion, releaseNotes)
            else -> UpdateState.UpToDate
        }
    }

    private fun currentVersionCode(context: Context): Long {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "UpdateChecker: versionCode lookup failed: ${e.message}")
            -1L
        }
    }
}
