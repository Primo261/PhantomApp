package top.niunaijun.blackboxa.view.main

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager
import top.niunaijun.blackboxa.R

class SlotCardAdapter(
    private val context: Context,
    private val onLaunchApp: (packageName: String, userId: Int) -> Unit,
    private val onAddApp: (userId: Int) -> Unit,
    private val onResetSlot: (userId: Int, position: Int) -> Unit,
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

    inner class SlotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSlotName:  TextView    = view.findViewById(R.id.tvSlotName)
        private val tvFpId:      TextView    = view.findViewById(R.id.tvFpId)
        private val tvModel:     TextView    = view.findViewById(R.id.tvModel)
        private val tvImei:      TextView    = view.findViewById(R.id.tvImei)
        private val tvAndroidId: TextView    = view.findViewById(R.id.tvAndroidId)
        private val rvApps:      RecyclerView= view.findViewById(R.id.rvAppsInSlot)
        private val btnReset:    TextView    = view.findViewById(R.id.btnReset)

        fun bind(slot: SlotData) {
            val userId = slot.userId

            // ── Nom du slot ───────────────────────────────────────────────────
            tvSlotName.text = getSlotName(userId)

            // Tap court = rien / Long press = rename dialog
            tvSlotName.setOnLongClickListener {
                showRenameDialog(userId)
                true
            }
            // Tap court aussi pour UX friendly
            tvSlotName.setOnClickListener {
                showRenameDialog(userId)
            }

            // ── Fingerprint display ───────────────────────────────────────────
            try {
                val fp = FingerprintManager.get()
                if (fp != null) {
                    val imei      = fp.getImei(userId)
                    val androidId = fp.getAndroidId(userId)
                    val model     = fp.getModel(userId)

                    tvFpId.text      = androidId.take(16)
                    tvModel.text     = if (model.length > 10) model.take(10) + "…" else model
                    tvImei.text      = if (imei.length > 8) imei.take(8) + "···" else imei
                    tvAndroidId.text = if (androidId.length > 8) androidId.take(8) + "···" else androidId
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

            // ── Reset button ──────────────────────────────────────────────────
            btnReset.setOnClickListener {
                try {
                    FingerprintManager.get()?.resetSlot(userId)
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_ID.toInt()) notifyItemChanged(pos)
                } catch (e: Exception) {
                    Log.w(TAG, "Reset error slot $userId: ${e.message}")
                }
                onResetSlot(userId, adapterPosition)
            }
        }

        // ── Dialog rename ─────────────────────────────────────────────────────
        private fun showRenameDialog(userId: Int) {
            val editText = EditText(context).apply {
                inputType   = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(getSlotName(userId))
                selectAll()
                filters     = arrayOf(InputFilter.LengthFilter(20))
                hint        = "Ex: Compte Vinted 2"
                setTextColor(0xFFC4A8FF.toInt())
                setHintTextColor(0xFF4A3870.toInt())
                background  = null
                setPadding(32, 24, 32, 16)
            }

            AlertDialog.Builder(context, R.style.PhantomDialogStyle)
                .setTitle("Renommer le slot")
                .setView(editText)
                .setPositiveButton("Valider") { _, _ ->
                    val newName = editText.text.toString().trim().ifBlank { "Slot ${userId + 1}" }
                    saveSlotName(userId, newName)
                    tvSlotName.text = newName
                }
                .setNegativeButton("Annuler", null)
                .show()
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
