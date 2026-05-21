package com.phantom.app.news

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.phantom.app.license.LicenseConfig
import top.niunaijun.blackboxa.R

/**
 * Binds a news_banner.xml root view to a NewsItem.
 *
 * Dismiss state is process-scoped (in-memory): closing the news in the current
 * session hides it for that session only, but it re-appears on the next cold
 * start. No SharedPreferences, no persistence.
 */
class NewsBanner(private val rootView: View) {

    private val tag = LicenseConfig.LOG_TAG

    companion object {
        // Process-wide dismissed ids. Reset when the OS kills the process.
        // No persistence on purpose — the user wants news to come back next
        // time the app is opened cold.
        private val dismissedIds: MutableSet<String> = mutableSetOf()
    }

    fun render(news: NewsItem?) {
        if (news == null) {
            hide()
            return
        }
        if (news.id in dismissedIds) {
            Log.d(tag, "NewsBanner: skipping dismissed (session) id=${news.id}")
            hide()
            return
        }
        Log.d(tag, "NewsBanner: rendering id=${news.id} type=${news.type}")
        rootView.visibility = View.VISIBLE

        val ctx = rootView.context
        val iconRes = when (news.type) {
            "warning" -> R.drawable.ic_warning
            "success" -> R.drawable.ic_success
            "error" -> R.drawable.ic_error
            else -> R.drawable.ic_info
        }
        val tintColor = when (news.type) {
            "warning" -> R.color.phantom_warning
            "success" -> R.color.phantom_success
            "error" -> R.color.phantom_error
            else -> R.color.phantom_accent_bright
        }
        val iconView = rootView.findViewById<ImageView>(R.id.news_icon)
        iconView.setImageResource(iconRes)
        iconView.setColorFilter(ContextCompat.getColor(ctx, tintColor))

        rootView.findViewById<TextView>(R.id.news_message).text = news.message

        val closeBtn = rootView.findViewById<ImageView>(R.id.news_close)
        if (news.dismissible) {
            closeBtn.visibility = View.VISIBLE
            closeBtn.setOnClickListener {
                dismissedIds.add(news.id)
                Log.d(tag, "NewsBanner: dismissed (session) id=${news.id}")
                rootView.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        rootView.visibility = View.GONE
                        rootView.alpha = 1f
                    }
                    .start()
            }
        } else {
            closeBtn.visibility = View.GONE
            closeBtn.setOnClickListener(null)
        }

        val link = news.link
        if (!link.isNullOrBlank()) {
            rootView.isClickable = true
            rootView.setOnClickListener {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                } catch (e: Exception) {
                    Log.w(tag, "NewsBanner: open link failed: ${e.message}")
                }
            }
        } else {
            rootView.isClickable = false
            rootView.setOnClickListener(null)
        }
    }

    private fun hide() {
        rootView.visibility = View.GONE
    }
}
