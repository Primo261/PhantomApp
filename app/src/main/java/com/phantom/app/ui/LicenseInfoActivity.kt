package com.phantom.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phantom.app.license.LicenseConfig
import com.phantom.app.license.LicenseStorage
import top.niunaijun.blackboxa.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LicenseInfoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = LicenseConfig.LOG_TAG
        private const val ETERNAL_THRESHOLD_SEC = 3_786_825_600L // ~year 2090

        fun start(context: Context) {
            context.startActivity(Intent(context, LicenseInfoActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license_info)
        Log.d(TAG, "LicenseInfoActivity.onCreate")

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val stored = try {
            LicenseStorage.getStoredLicense(this)
        } catch (e: Exception) {
            Log.e(TAG, "LicenseInfoActivity: storage error: ${e.message}")
            null
        }

        val labelView = findViewById<TextView>(R.id.tv_label)
        val expirationView = findViewById<TextView>(R.id.tv_expiration)
        val deviceIdView = findViewById<TextView>(R.id.tv_device_id)
        val activatedAtView = findViewById<TextView>(R.id.tv_activated_at)

        if (stored == null) {
            labelView.text = "—"
            expirationView.text = "—"
            deviceIdView.text = "—"
            activatedAtView.text = "Aucune licence active"
        } else {
            labelView.text = stored.payload.label?.ifBlank { "—" } ?: "—"
            expirationView.text = formatExpiry(stored.payload.expires_at)
            deviceIdView.text = stored.deviceId
            activatedAtView.text = if (stored.lastVerifiedAt > 0L) {
                "Vérifiée le ${formatDate(stored.lastVerifiedAt)}"
            } else {
                "Activée récemment"
            }
        }

        findViewById<Button>(R.id.btn_deactivate).setOnClickListener {
            confirmDeactivate()
        }
    }

    private fun confirmDeactivate() {
        MaterialAlertDialogBuilder(this, R.style.PhantomDialog)
            .setTitle("Désactiver la licence ?")
            .setMessage("La clé sera supprimée de ce device. Tu devras la ré-entrer pour réutiliser l'app.")
            .setPositiveButton("Désactiver") { _, _ ->
                try {
                    LicenseStorage.clearLicense(this)
                } catch (e: Exception) {
                    Log.e(TAG, "clearLicense failed: ${e.message}")
                }
                val intent = Intent(this, ActivationActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finishAffinity()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun formatExpiry(expiresAt: Long): String {
        if (expiresAt >= ETERNAL_THRESHOLD_SEC) return "Éternelle"
        return try {
            SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(Date(expiresAt * 1000L))
        } catch (e: Exception) {
            "—"
        }
    }

    private fun formatDate(epochMillis: Long): String = try {
        SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(Date(epochMillis))
    } catch (e: Exception) {
        "—"
    }
}
