package com.womp.recvault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

object Notif {
    const val CH_STATUS = "status"
    const val CH_ALARM = "alarm"

    const val ID_STATUS = 1
    const val ID_REC_STOPPED = 10
    const val ID_DRIVE = 11
    const val ID_ONEDRIVE = 12
    const val ID_STORAGE = 13
    const val ID_INTERNAL = 14

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Recording status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Backup ALARMS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when a recording is not backed up, or recording stopped."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                setBypassDnd(true)
            }
        )
    }

    private fun openApp(ctx: Context, resume: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_RESUME, resume)
        return PendingIntent.getActivity(
            ctx, if (resume) 2 else 1, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun status(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("RecVault recording")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, false))
            .build()

    fun updateStatus(ctx: Context, text: String) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(ctx, text))
    }

    /** Loud, sticky, can't be swiped away. Cleared automatically when the problem is fixed. */
    fun alarm(ctx: Context, id: Int, title: String, text: String, resume: Boolean = false) {
        val n = Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openApp(ctx, resume))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun clear(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
