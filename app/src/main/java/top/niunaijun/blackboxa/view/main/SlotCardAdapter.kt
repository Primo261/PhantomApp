package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
    private val onDeleteApp: (packageName: String, userId: Int) -> Unit
) : RecyclerView.Adapter<SlotCardAdapter.SlotViewHolder>() {

    companion object {
        private const val TAG = "SlotCardAdapter"
    }

    data class SlotData(val userId: Int)

    private val slots = mutableListOf<SlotData>()
    private val appsCache = mutableMapOf<Int, MutableList<ApplicationInfo>>()

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
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_slot_card, parent, false)
        return SlotViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        holder.bind(slots[position])
    }

    inner class SlotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSlotName: TextView  = view.findViewById(R.id.tvSlotName)
        private val tvFpId: TextView      = view.findViewById(R.id.tvFpId)
        private val tvImei: TextView      = view.findViewById(R.id.tvImei)
        private val tvMac: TextView       = view.findViewById(R.id.tvMac)
        private val tvAndroidId: TextView = view.findViewById(R.id.tvAndroidId)
        private val rvApps: RecyclerView  = view.findViewById(R.id.rvAppsInSlot)
        private val btnReset: TextView    = view.findViewById(R.id.btnReset)

        fun bind(slot: SlotData) {
            val userId = slot.userId
            tvSlotName.text = "Slot ${userId + 1}"

            // ── Fingerprint display ──────────────────────────────────────────
            try {
                val fp = FingerprintManager.get()
                if (fp != null) {
                    val imei      = fp.getImei(userId)
                    val mac       = fp.getWifiMac(userId)
                    val androidId = fp.getAndroidId(userId)
                    tvFpId.text      = androidId
                    tvImei.text      = imei.take(8) + "···"
                    tvMac.text       = mac.take(8)  + "···"
                    tvAndroidId.text = androidId.take(8) + "···"
                } else {
                    setFpPlaceholder()
                }
            } catch (e: Exception) {
                Log.w(TAG, "FP display error slot $userId: ${e.message}")
                setFpPlaceholder()
            }

            // ── Apps ─────────────────────────────────────────────────────────
            val apps = loadApps(userId)
            val appAdapter = SlotAppAdapter(
                context     = context,
                apps        = apps,
                onAppClick  = { app -> onLaunchApp(app.packageName, userId) },
                onAddClick  = { onAddApp(userId) },
                onAppDelete = { app ->
                    showDeleteConfirm(app, userId)
                }
            )
            rvApps.layoutManager = LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false
            )
            rvApps.adapter = appAdapter
            rvApps.isNestedScrollingEnabled = false

            // ── Reset button ─────────────────────────────────────────────────
            btnReset.setOnClickListener {
                try {
                    FingerprintManager.get()?.resetSlot(userId)
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_ID.toInt()) notifyItemChanged(pos)
                } catch (e: Exception) {
                    Log.w(TAG, "Reset error: ${e.message}")
                }
                onResetSlot(userId, adapterPosition)
            }
        }

        private fun showDeleteConfirm(app: ApplicationInfo, userId: Int) {
            try {
                val pm = context.packageManager
                val appName = try {
                    pm.getApplicationLabel(app).toString()
                } catch (e: Exception) {
                    app.packageName
                }
                android.app.AlertDialog.Builder(context)
                    .setTitle("Désinstaller du slot")
                    .setMessage("Supprimer $appName de ce slot ?")
                    .setPositiveButton("Supprimer") { _, _ ->
                        onDeleteApp(app.packageName, userId)
                        appsCache.remove(userId)
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_ID.toInt()) notifyItemChanged(pos)
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            } catch (e: Exception) {
                onDeleteApp(app.packageName, userId)
                refreshSlot(userId)
            }
        }

        private fun setFpPlaceholder() {
            tvFpId.text      = "Généré au premier lancement"
            tvImei.text      = "···"
            tvMac.text       = "···"
            tvAndroidId.text = "···"
        }

        private fun loadApps(userId: Int): MutableList<ApplicationInfo> {
            return appsCache.getOrPut(userId) {
                try {
                    (BlackBoxCore.get().getInstalledApplications(0, userId)
                        ?: emptyList<ApplicationInfo>()).toMutableList()
                } catch (e: Exception) {
                    Log.w(TAG, "loadApps error slot $userId: ${e.message}")
                    mutableListOf()
                }
            }
        }
    }
}
