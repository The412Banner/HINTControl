package com.banner.hintcontrol.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.banner.hintcontrol.HintApp
import com.banner.hintcontrol.data.toSummary
import com.banner.hintcontrol.work.RebootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Lets automation apps (Tasker, MacroDroid, Automate…) reboot the gateway or refresh the widget
 * with a broadcast. Off by default; every broadcast must carry the token shown in Settings.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val c = HintApp.container(context)
                val s = c.settings.current()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (!s.automationEnabled || s.automationToken.isEmpty() || !constantTimeEquals(token, s.automationToken)) return@launch
                when (intent.action) {
                    ACTION_REBOOT -> RebootService.start(context, scheduled = false)
                    ACTION_REFRESH -> {
                        runCatching {
                            val main = c.session.status { it.mainData() }
                            c.profiles.active.value?.let { p -> c.summaries.save(main.toSummary(p.name)) }
                        }
                        c.refreshSurfaces()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val ACTION_REBOOT = "com.banner.hintcontrol.action.REBOOT"
        const val ACTION_REFRESH = "com.banner.hintcontrol.action.REFRESH"
        const val EXTRA_TOKEN = "token"
    }
}
