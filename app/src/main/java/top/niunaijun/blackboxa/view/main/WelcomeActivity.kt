package top.niunaijun.blackboxa.view.main

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.view.list.ListViewModel

class WelcomeActivity : AppCompatActivity() {

    private data class LogStep(
        val text: String,
        val status: String,
        val progress: Int,
    )

    private val steps = listOf(
        LogStep("> Initializing Phantom Engine...",     "Démarrage du système", 20),
        LogStep("> Loading virtualization core...",      "Chargement du moteur", 45),
        LogStep("> Mounting isolated environments...",   "Préparation des slots", 65),
        LogStep("> Verifying license signature...",      "Vérification licence", 85),
        LogStep("> System ready.",                       "Prêt", 100),
    )

    private var splashJob: Job? = null
    @Volatile private var alreadyJumped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Warm the installed-apps cache on a background thread (existing
        // behavior preserved from the original WelcomeActivity).
        previewInstalledAppList()

        val logo        = findViewById<View>(R.id.splash_logo)
        val container   = findViewById<LinearLayout>(R.id.splash_log_container)
        val progressBar = findViewById<ProgressBar>(R.id.splash_progress)
        val statusText  = findViewById<TextView>(R.id.splash_status)

        ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).apply {
            duration = 400
            start()
        }

        splashJob = lifecycleScope.launch {
            delay(300)
            for ((index, step) in steps.withIndex()) {
                addLogLine(container, step.text)
                statusText.text = step.status
                animateProgressTo(progressBar, step.progress)
                delay(if (index == steps.size - 1) 300L else 450L)
            }
            delay(250)
            routeToNextScreen()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // App relaunch while we're still on the splash: skip remaining anim
        // and jump immediately so the user doesn't sit through it again.
        routeToNextScreen()
    }

    override fun onDestroy() {
        splashJob?.cancel()
        splashJob = null
        super.onDestroy()
    }

    private fun addLogLine(container: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.phantom_accent_bright))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            alpha = 0f
            setPadding(0, 4, 0, 4)
        }
        container.addView(tv)
        ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f).apply {
            duration = 250
            start()
        }
    }

    private fun animateProgressTo(bar: ProgressBar, target: Int) {
        ValueAnimator.ofInt(bar.progress, target).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
            start()
        }
    }

    /**
     * Routing preserved from the original WelcomeActivity: jump to MainActivity.
     * MainActivity.onCreate runs the license gate (LicenseGuard.isValid) and
     * redirects to ActivationActivity if invalid — no need to duplicate that
     * check here.
     */
    private fun routeToNextScreen() {
        if (alreadyJumped) return
        alreadyJumped = true
        MainActivity.start(this)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun previewInstalledAppList() {
        try {
            val viewModel = ViewModelProvider(this, InjectionUtil.getListFactory())
                .get(ListViewModel::class.java)
            viewModel.previewInstalledList()
        } catch (e: Exception) {
            // Cache warmup is best-effort; never block splash on it.
        }
    }
}
