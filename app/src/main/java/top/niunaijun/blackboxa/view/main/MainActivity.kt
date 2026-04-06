package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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
            catch (e: Exception) { Log.e(TAG, "onBeforeMainActivityOnCreate: ${e.message}") }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initSlotRecyclerView()
            initFab()
            checkStoragePermission()
            checkVpnPermission()

            try { BlackBoxCore.get().onAfterMainActivityOnCreate(this) }
            catch (e: Exception) { Log.e(TAG, "onAfterMainActivityOnCreate: ${e.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
            showErrorDialog("Failed to initialize: ${e.message}")
        }
    }

    // ─── Slots ────────────────────────────────────────────────────────────────

    private fun initSlotRecyclerView() {
        slotCardAdapter = SlotCardAdapter(
            context = this,
            onLaunchApp = { packageName, userId -> launchApp(packageName, userId) },
            onAddApp    = { userId -> openAppPicker(userId) },
            onResetSlot = { userId, _ -> slotCardAdapter.refreshSlot(userId) }
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
                Log.e(TAG, "Error getting users: ${e.message}")
                emptyList()
            }
            withContext(Dispatchers.Main) {
                val slotData = users.map { SlotCardAdapter.SlotData(it.id) }
                slotCardAdapter.setSlots(slotData)
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
                Log.e(TAG, "Error adding slot: ${e.message}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "Erreur création slot", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ─── App picker ───────────────────────────────────────────────────────────

    private fun openAppPicker(userId: Int) {
        val intent = Intent(this, ListActivity::class.java)
        intent.putExtra("userID", userId)
        apkPathResult.launch(intent)
    }

    private val apkPathResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                if (result.resultCode == RESULT_OK) {
                    result.data?.let { data ->
                        val userId = data.getIntExtra("userID", 0)
                        val source = data.getStringExtra("source")
                        if (source != null) installApp(source, userId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "apkPathResult error: ${e.message}")
            }
        }

    // ─── Install & Launch ─────────────────────────────────────────────────────

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
                Log.e(TAG, "installApp error: ${e.message}")
                withContext(Dispatchers.Main) {
                    hideLoading()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Installation échouée: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Conservés pour compatibilité avec AppsFragment
    fun showFloatButton(show: Boolean) {
        // No-op — plus de FAB lié au scroll dans la nouvelle UI
    }

    fun scanUser() {
        refreshSlots()
    }

    private fun launchApp(packageName: String, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { showLoading() }
                val success = BlackBoxCore.get().launchApk(packageName, userId)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    if (!success) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            getString(R.string.start_fail),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "launchApp error: ${e.message}")
                withContext(Dispatchers.Main) {
                    hideLoading()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.start_fail),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun checkStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) showStoragePermissionDialog()
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) requestLegacyStoragePermission()
            }
        } catch (e: Exception) { Log.e(TAG, "checkStoragePermission: ${e.message}") }
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
                message(text = "PhantomApp a besoin d'accès au stockage pour fonctionner correctement.")
                positiveButton(text = "Autoriser") { openAllFilesAccessSettings() }
                negativeButton(text = "Plus tard")
                cancelable(false)
            }
        } catch (e: Exception) { Log.e(TAG, "showStorageDialog: ${e.message}") }
    }

    private fun openAllFilesAccessSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                storagePermissionResult.launch(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .also { it.data = Uri.parse("package:$packageName") }
                )
            }
        } catch (e: Exception) { Log.e(TAG, "openAllFilesSettings: ${e.message}") }
    }

    private val storagePermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private fun checkVpnPermission() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) vpnPermissionResult.launch(vpnIntent)
        } catch (e: Exception) { Log.e(TAG, "checkVpnPermission: ${e.message}") }
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
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Primo261/PhantomApp"))
                )
                R.id.fake_location -> startActivity(
                    Intent(this, FakeManagerActivity::class.java).also { it.putExtra("userID", 0) }
                )
            }
            true
        } catch (e: Exception) { false }
    }
}
