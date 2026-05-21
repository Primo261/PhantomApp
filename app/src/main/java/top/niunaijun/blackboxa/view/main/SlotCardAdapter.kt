package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager
import top.niunaijun.blackboxa.R

class SlotCardAdapter(
    private val context: Context,
    private val onLaunchApp: (packageName: String, userId: Int) -> Unit,
    private val onAddApp: (userId: Int) -> Unit,
    private val onDeleteSlot: (userId: Int) -> Unit,
    private val onAppDelete: (ApplicationInfo, userId: Int) -> Unit
) : RecyclerView.Adapter<SlotCardAdapter.SlotViewHolder>() {

    companion object {
        private const val TAG  = "SlotCardAdapter"
        private const val PREFS_SLOT_NAMES = "phantom_slot_names"
    }

    data class SlotData(val userId: Int)

    private val slots     = mutableListOf<SlotData>()
    private val appsCache = mutableMapOf<Int, List<ApplicationInfo>>()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_SLOT_NAMES, Context.MODE_PRIVATE)

    // ── Nom personnalisé du slot ──────────────────────────────────────────────

    private fun getSlotName(userId: Int): String =
        prefs.getString("slot_name_$userId", null) ?: "Slot ${userId + 1}"

    private fun saveSlotName(userId: Int, name: String) {
        prefs.edit().putString("slot_name_$userId", name.trim().ifBlank { "Slot ${userId + 1}" }).apply()
    }

    // ── Données ───────────────────────────────────────────────────────────────

    fun setSlots(slotList: List<SlotData>) {
        slots.clear()
        slots.addAll(slotList)
        notifyDataSetChanged()
    }

    fun refreshSlot(userId: Int) {
        appsCache.remove(userId)
        val pos = slots.indexOfFirst { it.userId == userId }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun refreshAll() {
        appsCache.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount() = slots.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_slot_card, parent, false)
        return SlotViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        holder.bind(slots[position])
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class SlotViewHolder(private val rootView: View) : RecyclerView.ViewHolder(rootView) {
        private val tvSlotName:  TextView    = rootView.findViewById(R.id.tvSlotName)
        private val tvFpId:      TextView    = rootView.findViewById(R.id.tvFpId)
        private val tvModel:     TextView    = rootView.findViewById(R.id.tvModel)
        private val tvImei:      TextView    = rootView.findViewById(R.id.tvImei)
        private val tvAndroidId: TextView    = rootView.findViewById(R.id.tvAndroidId)
        private val rvApps:      RecyclerView= rootView.findViewById(R.id.rvAppsInSlot)
        private val btnDelete:   View        = rootView.findViewById(R.id.btn_delete_slot)

        fun bind(slot: SlotData) {
            val userId = slot.userId

            // ── Nom du slot ───────────────────────────────────────────────────
            tvSlotName.text = getSlotName(userId)

            // Tap directly on the name → rename. No pencil icon: the ripple
            // feedback on the TextView is the affordance (padding 4dp +
            // selectableItemBackgroundBorderless).
            tvSlotName.setOnClickListener { showRenameDialog(userId) }

            // Red X overlay → delete confirm.
            btnDelete.setOnClickListener { showDeleteDialog(userId) }

            // Long-press anywhere on the card opens the actions bottom sheet
            // (rename / delete in a single sheet — kept as a backup affordance).
            val longPress = View.OnLongClickListener {
                showActionsSheet(userId)
                true
            }
            rootView.setOnLongClickListener(longPress)
            tvSlotName.setOnLongClickListener(longPress)

            // ── Fingerprint display ───────────────────────────────────────────
            try {
                val fp = FingerprintManager.get()
                if (fp != null) {
                    val imei      = fp.getImei(userId)
                    val androidId = fp.getAndroidId(userId)
                    val model     = fp.getModel(userId)

                    tvFpId.text      = androidId.take(16)
                    tvModel.text     = if (model.length > 12) model.take(12) + "…" else model
                    tvImei.text      = if (imei.length > 10) imei.take(10) + "···" else imei
                    tvAndroidId.text = if (androidId.length > 10) androidId.take(10) + "···" else androidId
                } else {
                    setFpGenerating()
                }
            } catch (e: Exception) {
                Log.w(TAG, "FP display error slot $userId: ${e.message}")
                setFpGenerating()
            }

            // ── Apps list ─────────────────────────────────────────────────────
            val apps = loadApps(userId).toMutableList()
            val appAdapter = SlotAppAdapter(
                context    = context,
                apps       = apps,
                onAppClick = { app -> onLaunchApp(app.packageName, userId) },
                onAddClick = { onAddApp(userId) },
                onAppDelete = { app -> onAppDelete(app, userId) }
            )
            rvApps.layoutManager = LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false
            )
            rvApps.adapter = appAdapter
            rvApps.isNestedScrollingEnabled = false
        }

        // ── Bottom sheet ──────────────────────────────────────────────────────

        private fun showActionsSheet(userId: Int) {
            val activity = context as? FragmentActivity ?: return
            val sheet = SlotActionsBottomSheet().configure(
                slotName = getSlotName(userId),
                onRename = { showRenameDialog(userId) },
                onDelete = { showDeleteDialog(userId) },
            )
            sheet.show(activity.supportFragmentManager, "slot_actions_${userId}")
        }

        // ── Custom rename dialog ──────────────────────────────────────────────

        private fun showRenameDialog(userId: Int) {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_rename_slot, null, false)
            val input = view.findViewById<EditText>(R.id.rename_input).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                filters = arrayOf(InputFilter.LengthFilter(20))
                setText(getSlotName(userId))
                selectAll()
            }

            val dialog = AlertDialog.Builder(context, R.style.PhantomDialogTheme)
                .setView(view)
                .create()

            view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
            view.findViewById<Button>(R.id.btn_save).setOnClickListener {
                val newName = input.text.toString().trim().ifBlank { "Slot ${userId + 1}" }
                saveSlotName(userId, newName)
                tvSlotName.text = newName
                dialog.dismiss()
            }
            dialog.show()
        }

        // ── Custom delete dialog ──────────────────────────────────────────────

        private fun showDeleteDialog(userId: Int) {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_delete_slot, null, false)
            val dialog = AlertDialog.Builder(context, R.style.PhantomDialogTheme)
                .setView(view)
                .create()

            view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
            view.findViewById<Button>(R.id.btn_confirm).setOnClickListener {
                dialog.dismiss()
                try {
                    BlackBoxCore.get().deleteUser(userId)
                } catch (e: Exception) {
                    Log.e(TAG, "deleteUser($userId) failed: ${e.message}", e)
                }
                onDeleteSlot(userId)
            }
            dialog.show()
        }

        private fun setFpGenerating() {
            tvFpId.text      = "Généré au 1er lancement"
            tvModel.text     = "···"
            tvImei.text      = "···"
            tvAndroidId.text = "···"
        }

        private fun loadApps(userId: Int): List<ApplicationInfo> {
            return appsCache.getOrPut(userId) {
                try {
                    BlackBoxCore.get().getInstalledApplications(0, userId) ?: emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "loadApps error slot $userId: ${e.message}")
                    emptyList()
                }
            }
        }
    }
}
