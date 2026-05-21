package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.snackbar.Snackbar
import com.phantom.app.license.LicenseGuard
import com.phantom.app.license.LicenseWatchdog
import com.phantom.app.news.NewsApi
import com.phantom.app.news.NewsBanner
import com.phantom.app.ui.ActivationActivity
import com.phantom.app.ui.ForceUpdateActivity
import com.phantom.app.ui.LicenseInfoActivity
import com.phantom.app.update.UpdateChecker
import com.phantom.app.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import java.io.File

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()
    private lateinit var slotCardAdapter: SlotCardAdapter
    private var newsBanner: NewsBanner? = null
    // Toolbar / FAB views are now inline in activity_main.xml as
    // btn_license_info / btn_fab (exposed via viewBinding) — the old
    // view_toolbar include and its ivLogo/ivSettings ids are gone.

    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001

        fun start(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            // Local-only gate. No network, no coroutine suspension — sub-50ms
            // total (signature verify + expiry check + clock-rollback check).
            // This replaces the previous synchronous `runBlocking` that could
            // sit up to 6s waiting on /api/verify and produce an ANR.
            val localOk = try {
                LicenseGuard.isValidLocalOnly(this@MainActivity)
            } catch (e: Exception) {
                Log.e(TAG, "License local gate threw: ${e.message}", e)
                false
            }
            if (!localOk) {
                Log.w(TAG, "License local gate refused boot -> ActivationActivity")
                startActivity(Intent(this, ActivationActivity::class.java))
                finish()
                return
            }

            // Force-init du FingerprintManager avec un context applicatif
            // garanti, avant que le RecyclerView des slots fasse son premier
            // bind. Sans ça, FingerprintManager.get() peut renvoyer null si
            // BlackBoxCore.getContext() n'est pas encore prêt, et les cards
            // s'affichent en "···".
            try { FingerprintManager.init(applicationContext) }
            catch (e: Exception) { Log.e(TAG, "FP init: ${e.message}") }

            try { BlackBoxCore.get().onBeforeMainActivityOnCreate(this) }
            catch (e: Exception) { Log.e(TAG, "onBefore: ${e.message}") }

            setContentView(viewBinding.root)
            setupPhantomHeader()
            initSlotRecyclerView()
            initFab()
            checkStoragePermission()
            checkVpnPermission()

            try { BlackBoxCore.get().onAfterMainActivityOnCreate(this) }
            catch (e: Exception) { Log.e(TAG, "onAfter: ${e.message}") }

            // Background online reverify. If the server says invalid we wipe
            // and kick to ActivationActivity. Network failures (timeout, etc.)
            // are silent — the watchdog and the next cold-boot will retry.
            backgroundReverify()
        } catch (e: Exception) {
            Log.e(TAG, "Critical onCreate: ${e.message}")
            showErrorDialog("Failed to initialize: ${e.message}")
        }
    }

    private fun backgroundReverify() {
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = try {
                LicenseGuard.reverifyOnce(this@MainActivity)
            } catch (e: Exception) {
                Log.w(TAG, "backgroundReverify threw: ${e.message}")
                return@launch
            }
            if (outcome == LicenseGuard.OnlineReverifyOutcome.INVALID ||
                outcome == LicenseGuard.OnlineReverifyOutcome.NO_LICENSE) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Log.w(TAG, "backgroundReverify: $outcome -> ActivationActivity")
                    startActivity(
                        Intent(this@MainActivity, ActivationActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LicenseWatchdog.start(this)
        checkForUpdates()
        fetchNews()
    }

    override fun onPause() {
        LicenseWatchdog.stop()
        super.onPause()
    }

    // ─── Update gate ──────────────────────────────────────────────────────────

    @Volatile private var updateCheckInFlight = false

    private fun checkForUpdates() {
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        lifecycleScope.launch {
            try {
                val state = try {
                    UpdateChecker.check(this@MainActivity)
                } catch (e: Exception) {
                    Log.w(TAG, "UpdateChecker threw: ${e.message}")
                    UpdateState.UpToDate
                }
                if (isFinishing || isDestroyed) return@launch
                when (state) {
                    is UpdateState.Force -> {
                        Log.w(TAG, "Update FORCED -> ForceUpdateActivity")
                        ForceUpdateActivity.start(
                            this@MainActivity,
                            state.updateUrl,
                            state.latestVersion,
                            state.releaseNotes,
                        )
                    }
                    is UpdateState.Optional -> {
                        Log.d(TAG, "Update available (optional)")
                        showOptionalUpdateSnackbar(state)
                    }
                    is UpdateState.UpToDate -> {
                        Log.d(TAG, "App is up to date")
                    }
                }
            } finally {
                updateCheckInFlight = false
            }
        }
    }

    private fun fetchNews() {
        val rootView = findViewById<android.view.View>(R.id.news_banner_root) ?: return
        lifecycleScope.launch {
            val item = try {
                NewsApi.fetch()
            } catch (e: Exception) {
                Log.w(TAG, "fetchNews threw: ${e.message}")
                null
            }
            if (isFinishing || isDestroyed) return@launch
            try {
                val banner = newsBanner ?: NewsBanner(rootView).also { newsBanner = it }
                banner.render(item)
            } catch (e: Exception) {
                Log.w(TAG, "fetchNews: render failed: ${e.message}")
            }
        }
    }

    private fun showOptionalUpdateSnackbar(state: UpdateState.Optional) {
        try {
            Snackbar.make(
                viewBinding.root,
                "Mise à jour disponible (${state.latestVersion})",
                Snackbar.LENGTH_LONG,
            ).setAction("Mettre à jour") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.updateUrl)))
                } catch (e: Exception) {
                    Log.e(TAG, "openUpdateUrl failed: ${e.message}")
                }
            }.show()
        } catch (e: Exception) {
            Log.w(TAG, "snackbar show failed: ${e.message}")
        }
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupPhantomHeader() {
        viewBinding.btnLicenseInfo.setOnClickListener {
            LicenseInfoActivity.start(this)
        }
    }

    // ─── Slots ────────────────────────────────────────────────────────────────

    private fun initSlotRecyclerView() {
        slotCardAdapter = SlotCardAdapter(
            context      = this,
            onLaunchApp  = { pkg, userId -> launchApp(pkg, userId) },
            onAddApp     = { userId -> openAppPicker(userId) },
            onDeleteSlot = { userId -> onSlotDeleted(userId) },
            onAppDelete  = { app: ApplicationInfo, userId -> deleteApp(app.packageName, userId) }
        )
        viewBinding.slotsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = slotCardAdapter
            setHasFixedSize(false)
        }
        refreshSlots()
    }

    private fun refreshSlots() {
        lifecycleScope.launch(Dispatchers.IO) {
            val users = try {
                BlackBoxCore.get().users ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getUsers: ${e.message}")
                emptyList()
            }
            withContext(Dispatchers.Main) {
                slotCardAdapter.setSlots(users.map { SlotCardAdapter.SlotData(it.id) })
            }
        }
    }

    private fun initFab() {
        viewBinding.btnFab.setOnClickListener { addNewSlot() }
    }

    private fun onSlotDeleted(@Suppress("UNUSED_PARAMETER") userId: Int) {
        refreshSlots()
    }

    private fun addNewSlot() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val users = BlackBoxCore.get().users ?: emptyList()
                BlackBoxCore.get().createUser(users.size)
                withContext(Dispatchers.Main) { refreshSlots() }
            } catch (e: Exception) {
                Log.e(TAG, "addSlot: ${e.message}")
            }
        }
    }

    // ─── App picker ───────────────────────────────────────────────────────────

    private var currentPickerUserId = 0

    private fun openAppPicker(userId: Int) {
        currentPickerUserId = userId
        val intent = Intent(this, ListActivity::class.java)
        intent.putExtra("userID", userId)
        apkPathResult.launch(intent)
    }

    private val apkPathResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                if (result.resultCode == RESULT_OK) {
                    result.data?.let { data ->
                        val userId = data.getIntExtra("userID", currentPickerUserId)
                        val source = data.getStringExtra("source")
                        if (source != null) installApp(source, userId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "apkPathResult: ${e.message}")
            }
        }

    // ─── Install / Launch / Delete ────────────────────────────────────────────

    private fun installApp(source: String, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { showLoading() }
                if (File(source).exists()) {
                    BlackBoxCore.get().installPackageAsUser(File(source), userId)
                } else {
                    BlackBoxCore.get().installPackageAsUser(source, userId)
                }
                withContext(Dispatchers.Main) {
                    hideLoading()
                    slotCardAdapter.refreshSlot(userId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "installApp: ${e.message}")
                withContext(Dispatchers.Main) { hideLoading() }
            }
        }
    }

    private fun launchApp(packageName: String, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { showLoading() }
                val success = BlackBoxCore.get().launchApk(packageName, userId)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    if (!success) android.widget.Toast.makeText(
                        this@MainActivity, getString(R.string.start_fail),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "launchApp: ${e.message}")
                withContext(Dispatchers.Main) { hideLoading() }
            }
        }
    }

    private fun deleteApp(packageName: String, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { showLoading() }
                BlackBoxCore.get().uninstallPackageAsUser(packageName, userId)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    slotCardAdapter.refreshSlot(userId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteApp: ${e.message}")
                withContext(Dispatchers.Main) { hideLoading() }
            }
        }
    }

    // Compat AppsFragment
    fun showFloatButton(show: Boolean) {}
    fun scanUser() { refreshSlots() }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun checkStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager())
                    showStoragePermissionDialog()
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) requestLegacyStoragePermission()
            }
        } catch (e: Exception) { Log.e(TAG, "checkStorage: ${e.message}") }
    }

    private fun requestLegacyStoragePermission() {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            STORAGE_PERMISSION_REQUEST_CODE
        )
    }

    private fun showStoragePermissionDialog() {
        try {
            MaterialDialog(this).show {
                title(text = "Permission requise")
                message(text = "PhantomApp a besoin d'accès au stockage.")
                positiveButton(text = "Autoriser") { openAllFilesAccessSettings() }
                negativeButton(text = "Plus tard")
                cancelable(false)
            }
        } catch (e: Exception) { Log.e(TAG, "storageDialog: ${e.message}") }
    }

    private fun openAllFilesAccessSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                storagePermissionResult.launch(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .also { it.data = Uri.parse("package:$packageName") }
                )
            }
        } catch (e: Exception) { Log.e(TAG, "openStorage: ${e.message}") }
    }

    private val storagePermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private fun checkVpnPermission() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) vpnPermissionResult.launch(vpnIntent)
        } catch (e: Exception) { Log.e(TAG, "checkVpn: ${e.message}") }
    }

    private val vpnPermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = "Erreur")
                message(text = message)
                positiveButton(text = "OK") { finish() }
            }
        } catch (e: Exception) { finish() }
    }
}
