package com.experimental.gcam100x

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** "Pro Res Zoom" progress notification posted from the camera's process. */
class ProgressNotification(private val ctx: Context, private val id: Int) {
    companion object { private const val CHANNEL = "gcam100x_prozoom" }

    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        runCatching {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pro Res Zoom", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun builder(text: String) = Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(ctx.applicationInfo.icon)
        .setContentTitle("Pro Res Zoom")
        .setContentText(text)
        .setOnlyAlertOnce(true)

    fun show(text: String, percent: Int) {
        runCatching {
            nm.notify(id, builder("$text… $percent%").setOngoing(true).setProgress(100, percent, percent == 0).build())
        }
    }

    fun done(text: String) {
        runCatching {
            nm.notify(id, builder(text).setOngoing(false).setAutoCancel(true).setTimeoutAfter(5000).build())
        }
    }
}
