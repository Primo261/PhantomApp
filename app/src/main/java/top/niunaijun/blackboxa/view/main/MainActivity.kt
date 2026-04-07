package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity
import java.io.File

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()
    private lateinit var slotCardAdapter: SlotCardAdapter

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
            try { BlackBoxCore.get().onBeforeMainActivityOnCreate(this) }
            catch (e: Exception) { Log.e(TAG, "onBefore: ${e.message}") }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initSlotRecyclerView()
            initFab()
            checkStoragePermission()
            checkVpnPermission()

            try { BlackBoxCore.get().onAfterMainActivityOnCreate(this) }
            catch (e: Exception) { Log.e(TAG, "onAfter: ${e.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "Critical onCreate: ${e.message}")
            showErrorDialog("Failed to initialize: ${e.message}")
        }
    }

    // ─── Slots ────────────────────────────────────────────────────────────────

    private fun initSlotRecyclerView() {
        slotCardAdapter = SlotCardAdapter(
            context      = this,
            onLaunchApp  = { pkg, userId -> launchApp(pkg, userId) },
            onAddApp     = { userId -> openAppPicker(userId) },
            onResetSlot  = { userId, _ -> slotCardAdapter.refreshSlot(userId) },
            onDeleteApp  = { pkg, userId -> deleteApp(pkg, userId) }
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
        viewBinding.fab.setOnClickListener { addNewSlot() }
    }

    private fun addNewSlot() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val users = BlackBoxCore.get().users ?: emptyList()
                BlackBoxCore.get().createUser(users.size)
                withContext(Dispatchers.Main) { refreshSlots() }
            } catch (e: Exception) {
                Log.e(TAG, "addSlot: ${e.message}")
                withContext(Dispatchers.Main) {
                    toast("Erreur création slot: ${e.message}")
                }
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
                withContext(Dispatchers.Main) {
                    hideLoading()
                    toast("Installation échouée: ${e.message}")
                }
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
                    if (!success) toast(getString(R.string.start_fail))
                }
            } catch (e: Exception) {
                Log.e(TAG, "launchApp: ${e.message}")
                withContext(Dispatchers.Main) {
                    hideLoading()
                    toast(getString(R.string.start_fail))
                }
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
                    toast("App désinstallée du slot ${userId + 1}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteApp: ${e.message}")
                withContext(Dispatchers.Main) {
                    hideLoading()
                    toast("Erreur désinstallation: ${e.message}")
                }
            }
        }
    }

    // Compatibilité AppsFragment
    fun showFloatButton(show: Boolean) {}
    fun scanUser() { refreshSlots() }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

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

    // ─── Menu ─────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        return try { menuInflater.inflate(R.menu.menu_main, menu); true }
        catch (e: Exception) { false }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            when (item.itemId) {
                R.id.main_setting -> SettingActivity.start(this)
                R.id.main_git -> startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Primo261/PhantomApp"))
                )
                R.id.fake_location -> startActivity(
                    Intent(this, FakeManagerActivity::class.java)
                        .also { it.putExtra("userID", 0) }
                )
            }
            true
        } catch (e: Exception) { false }
    }
}
