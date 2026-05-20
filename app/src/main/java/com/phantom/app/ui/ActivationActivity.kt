package com.phantom.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phantom.app.license.ActivationResult
import com.phantom.app.license.LicenseConfig
import com.phantom.app.license.LicenseGuard
import com.phantom.app.license.LicenseParser
import kotlinx.coroutines.launch
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.MainActivity

class ActivationActivity : AppCompatActivity() {

    private companion object {
        const val TAG = LicenseConfig.LOG_TAG
    }

    private lateinit var licenseInput: EditText
    private lateinit var activateBtn: Button
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var contactLink: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "ActivationActivity.onCreate")
        setContentView(R.layout.activity_activation)

        licenseInput = findViewById(R.id.license_input)
        activateBtn = findViewById(R.id.activate_btn)
        loading = findViewById(R.id.loading)
        errorText = findViewById(R.id.error_text)
        contactLink = findViewById(R.id.contact_link)

        installLicenseFormatter()

        activateBtn.setOnClickListener { onActivateClicked() }
        contactLink.setOnClickListener { openWhatsApp() }
    }

    private fun onActivateClicked() {
        val raw = licenseInput.text?.toString().orEmpty().trim()
        Log.d(TAG, "onActivateClicked: input='${raw.take(32)}'")
        hideKeyboard()
        hideError()

        val normalized = LicenseParser.normalize(raw)
        if (normalized == null || !LicenseParser.isValidFormat(normalized)) {
            showError("Format de license invalide")
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = try {
                LicenseGuard.activate(this@ActivationActivity, normalized)
            } catch (e: Exception) {
                Log.e(TAG, "activate threw: ${e.message}", e)
                ActivationResult.Failure("internal", "Erreur interne. Réessaye.")
            }
            setBusy(false)
            when (result) {
                is ActivationResult.Success -> {
                    Log.d(TAG, "activation Success — launching MainActivity")
                    startActivity(
                        Intent(this@ActivationActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
                is ActivationResult.Failure -> {
                    Log.w(TAG, "activation Failure: ${result.reason} -> ${result.userMessage}")
                    showError(result.userMessage)
                }
            }
        }
    }

    private fun openWhatsApp() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(LicenseConfig.WHATSAPP_CONTACT_URL))
            )
        } catch (e: Exception) {
            Log.e(TAG, "openWhatsApp failed: ${e.message}")
            showError("Impossible d'ouvrir WhatsApp")
        }
    }

    private fun setBusy(busy: Boolean) {
        activateBtn.isEnabled = !busy
        loading.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(licenseInput.windowToken, 0)
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Auto-format input as the user types: insert dashes every 5 chars of the
     * payload (after the PHANTOM- prefix). Tolerant of paste/edit; debounced
     * via a re-entrancy guard.
     */
    private fun installLicenseFormatter() {
        licenseInput.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing || s == null) return
                editing = true
                try {
                    val formatted = formatLicense(s.toString())
                    if (formatted != s.toString()) {
                        licenseInput.setText(formatted)
                        licenseInput.setSelection(formatted.length.coerceAtMost(licenseInput.text.length))
                    }
                } finally {
                    editing = false
                }
            }
        })
    }

    private fun formatLicense(input: String): String {
        val cleaned = input.uppercase().replace(Regex("[^0-9A-Z]"), "")
        val body = if (cleaned.startsWith("PHANTOM")) cleaned.removePrefix("PHANTOM") else cleaned
        val sb = StringBuilder(LicenseConfig.LICENSE_PREFIX)
        val limited = body.take(20)
        for (i in limited.indices) {
            if (i > 0 && i % 5 == 0) sb.append('-')
            sb.append(limited[i])
        }
        return sb.toString()
    }
}
