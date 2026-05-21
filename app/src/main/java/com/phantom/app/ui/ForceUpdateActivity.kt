package com.phantom.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.phantom.app.license.LicenseConfig
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R

class ForceUpdateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = LicenseConfig.LOG_TAG
        const val EXTRA_UPDATE_URL = "update_url"
        const val EXTRA_LATEST_VERSION = "version"
        const val EXTRA_RELEASE_NOTES = "release_notes"

        fun start(activity: Activity, updateUrl: String, latestVersion: String, releaseNotes: String) {
            val intent = Intent(activity, ForceUpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_UPDATE_URL, updateUrl)
                putExtra(EXTRA_LATEST_VERSION, latestVersion)
                putExtra(EXTRA_RELEASE_NOTES, releaseNotes)
            }
            activity.startActivity(intent)
            activity.finishAffinity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kill every running slot app before showing the gate. Without this,
        // a force-update screen on the host doesn't stop background slot
        // processes (e.g. Vinted in :p0) — the user can keep using the
        // virtualised app despite being "forced" to update.
        killAllSlotProcesses()
        setContentView(R.layout.activity_force_update)
        Log.d(TAG, "ForceUpdateActivity.onCreate")

        val updateUrl = intent.getStringExtra(EXTRA_UPDATE_URL).orEmpty()
        val latestVersion = intent.getStringExtra(EXTRA_LATEST_VERSION).orEmpty()
        val releaseNotes = intent.getStringExtra(EXTRA_RELEASE_NOTES).orEmpty()

        findViewById<TextView>(R.id.tv_version).text =
            if (latestVersion.isNotBlank()) "Version $latestVersion disponible"
            else "Une nouvelle version est requise"

        findViewById<TextView>(R.id.tv_notes).text =
            if (releaseNotes.isNotBlank()) releaseNotes else "—"

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            openUrl(updateUrl)
        }
        findViewById<Button>(R.id.btn_quit).setOnClickListener {
            Log.d(TAG, "ForceUpdate: quit pressed")
            finishAffinity()
        }

        // Block back: user must update or quit explicitly.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "ForceUpdate: back blocked")
            }
        })
    }

    private fun killAllSlotProcesses() {
        try {
            val core = BlackBoxCore.get()
            val users = core.users ?: return
            for (user in users) {
                val userId = user.id
                val apps = try {
                    core.getInstalledApplications(0, userId)
                } catch (e: Exception) {
                    Log.w(TAG, "kill slots: getInstalledApplications($userId) failed: ${e.message}")
                    continue
                } ?: continue
                for (app in apps) {
                    try {
                        core.stopPackage(app.packageName, userId)
                    } catch (e: Exception) {
                        Log.w(TAG, "kill slots: stopPackage(${app.packageName}, $userId) failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "kill slots: outer failure: ${e.message}")
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) {
            Log.w(TAG, "ForceUpdate: empty update URL")
            Toast.makeText(this, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "ForceUpdate: openUrl failed: ${e.message}")
            Toast.makeText(this, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show()
        }
    }
}
