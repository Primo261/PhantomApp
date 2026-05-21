package com.phantom.app.license

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.phantom.app.ui.ActivationActivity
import com.phantom.app.util.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Periodic online re-verify while the activity is in foreground.
 *
 * Tick cadence: WATCHDOG_INTERVAL_MS (60s). On any tick where the server says
 * the license is invalid, storage is wiped and the user is rerouted to
 * ActivationActivity. Network errors (timeout, no connectivity) are silent —
 * the watchdog will retry on the next tick. The boot-time gate in
 * LicenseGuard.isValid handles the "offline beyond grace" case at next launch.
 */
object LicenseWatchdog {

    private const val TAG = LicenseConfig.LOG_TAG

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var activityRef: WeakReference<Activity>? = null

    @Synchronized
    fun start(activity: Activity) {
        if (job?.isActive == true) {
            Slog.d(TAG, "Watchdog: already running, ignoring start()")
            return
        }
        Slog.d(TAG, "Watchdog: starting on ${activity.javaClass.simpleName}")
        activityRef = WeakReference(activity)
        val appContext = activity.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                delay(LicenseConfig.WATCHDOG_INTERVAL_MS)
                if (!isActive) break
                Slog.d(TAG, "Watchdog: tick — calling reverifyOnce")
                val outcome = try {
                    LicenseGuard.reverifyOnce(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog: reverifyOnce threw ${e.javaClass.simpleName}: ${e.message}")
                    LicenseGuard.OnlineReverifyOutcome.UNREACHABLE
                }
                Slog.d(TAG, "Watchdog: outcome=$outcome")
                when (outcome) {
                    LicenseGuard.OnlineReverifyOutcome.INVALID,
                    LicenseGuard.OnlineReverifyOutcome.NO_LICENSE -> {
                        kickToActivation()
                        return@launch
                    }
                    LicenseGuard.OnlineReverifyOutcome.VALID,
                    LicenseGuard.OnlineReverifyOutcome.UNREACHABLE -> {
                        // Continue polling.
                    }
                }
            }
            Slog.d(TAG, "Watchdog: loop exited")
        }
    }

    @Synchronized
    fun stop() {
        if (job == null && scope == null) return
        Slog.d(TAG, "Watchdog: stop()")
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        activityRef = null
    }

    private fun kickToActivation() {
        val activity = activityRef?.get()
        if (activity == null) {
            Log.w(TAG, "Watchdog: activity ref gone, cannot kick to ActivationActivity")
            return
        }
        Log.w(TAG, "Watchdog: license invalid — kicking to ActivationActivity")
        activity.runOnUiThread {
            try {
                val intent = Intent(activity, ActivationActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                activity.startActivity(intent)
                activity.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog: failed to launch ActivationActivity: ${e.message}", e)
            }
        }
    }
}
