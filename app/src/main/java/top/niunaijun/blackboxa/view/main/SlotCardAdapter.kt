package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val onResetSlot: (userId: Int, position: Int) -> Unit
) : RecyclerView.Adapter<SlotCardAdapter.SlotViewHolder>() {

    companion object {
        private const val TAG = "SlotCardAdapter"
    }

    data class SlotData(val userId: Int)

    private val slots = mutableListOf<SlotData>()
    private val appsCache = mutableMapOf<Int, List<ApplicationInfo>>()

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

    inner class SlotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSlotName: TextView    = view.findViewById(R.id.tvSlotName)
        private val tvFpId: TextView        = view.findViewById(R.id.tvFpId)
        private val tvImei: TextView        = view.findViewById(R.id.tvImei)
        private val tvMac: TextView         = view.findViewById(R.id.tvMac)
        private val tvAndroidId: TextView   = view.findViewById(R.id.tvAndroidId)
        private val rvApps: RecyclerView    = view.findViewById(R.id.rvAppsInSlot)
        private val btnReset: TextView      = view.findViewById(R.id.btnReset)

        fun bind(slot: SlotData) {
            val userId = slot.userId
            tvSlotName.text = "Slot ${userId + 1}"

            // ── Fingerprint display ──────────────────────────────────────────
            // Note : FingerprintManager tourne dans le processus HOST.
            // Les valeurs affichées ici sont celles stockées pour ce slot.
            // Elles correspondent aux valeurs injectées dans le processus slot.
            try {
                val fp = FingerprintManager.get()
                if (fp != null) {
                    val imei      = fp.getImei(userId)
                    val mac       = fp.getWifiMac(userId)
                    val androidId = fp.getAndroidId(userId)

                    tvFpId.text      = androidId
                    tvImei.text      = if (imei.length > 8) imei.take(8) + "···" else imei
                    tvMac.text       = if (mac.length > 8)  mac.take(8)  + "···" else mac
                    tvAndroidId.text = if (androidId.length > 8) androidId.take(8) + "···" else androidId
                } else {
                    setFpGenerating()
                }
            } catch (e: Exception) {
                Log.w(TAG, "FP display error slot $userId: ${e.message}")
                setFpGenerating()
            }

            // ── Apps list ────────────────────────────────────────────────────
            val apps = loadApps(userId)
            val appAdapter = SlotAppAdapter(
                context    = context,
                apps       = apps,
                onAppClick = { app -> onLaunchApp(app.packageName, userId) },
                onAddClick = { onAddApp(userId) }
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
                    Log.w(TAG, "Reset error slot $userId: ${e.message}")
                }
                onResetSlot(userId, adapterPosition)
            }
        }

        private fun setFpGenerating() {
            tvFpId.text      = "Généré au premier lancement"
            tvImei.text      = "···"
            tvMac.text       = "···"
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
