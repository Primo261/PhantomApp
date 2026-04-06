package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class SlotAppAdapter(
    private val context: Context,
    private val apps: List<ApplicationInfo>,
    private val onAppClick: (ApplicationInfo) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_ADD = 1
    }

    override fun getItemViewType(position: Int) =
        if (position < apps.size) TYPE_APP else TYPE_ADD

    override fun getItemCount() = apps.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_APP) {
            val v = LayoutInflater.from(context).inflate(R.layout.item_app_in_slot, parent, false)
            AppViewHolder(v)
        } else {
            val v = LayoutInflater.from(context).inflate(R.layout.item_add_in_slot, parent, false)
            AddViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AppViewHolder && position < apps.size) {
            holder.bind(apps[position])
        } else if (holder is AddViewHolder) {
            holder.itemView.findViewById<LinearLayout>(R.id.btnAddApp)?.setOnClickListener {
                onAddClick()
            }
        }
    }

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = view.findViewById(R.id.tvAppName)

        fun bind(app: ApplicationInfo) {
            try {
                val pm = context.packageManager
                ivIcon.setImageDrawable(pm.getApplicationIcon(app.packageName))
                tvName.text = pm.getApplicationLabel(app).toString()
            } catch (e: Exception) {
                try {
                    ivIcon.setImageDrawable(context.packageManager.getApplicationIcon(app))
                } catch (e2: Exception) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                tvName.text = app.packageName.substringAfterLast(".")
            }
            itemView.setOnClickListener { onAppClick(app) }
        }
    }

    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
