package com.banner.hintcontrol.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.banner.hintcontrol.MainActivity
import com.banner.hintcontrol.R

class Notifier(private val context: Context) {
    enum class Channel(val id: String, val title: String, val importance: Int) {
        Alerts("alerts", "Gateway alerts", NotificationManager.IMPORTANCE_DEFAULT),
        Reboots("reboots", "Reboots", NotificationManager.IMPORTANCE_LOW),
        Devices("devices", "New devices", NotificationManager.IMPORTANCE_DEFAULT),
        Updates("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT),
        Work("work", "Background tasks", NotificationManager.IMPORTANCE_MIN),
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        Channel.entries.forEach { nm.createNotificationChannel(NotificationChannel(it.id, it.title, it.importance)) }
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(link: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            link?.let { data = Uri.parse("hintcontrol://$it") }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun build(channel: Channel, title: String, text: String, link: String? = null) =
        NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(link, channel.ordinal))
            .setAutoCancel(true)

    fun show(id: Int, channel: Channel, title: String, text: String, link: String? = null) {
        if (!canPost()) return
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, build(channel, title, text, link).build())
    }

    companion object {
        const val ID_OFFLINE = 10
        const val ID_REBOOT = 11
        const val ID_WEAK = 12
        const val ID_DEVICE = 13
        const val ID_SCHEDULED = 14
        const val ID_UPDATE = 15
        const val ID_SERVICE = 16
        const val ID_FIRMWARE = 17
    }
}
