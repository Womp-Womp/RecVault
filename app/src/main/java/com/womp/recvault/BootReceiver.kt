package com.womp.recvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or app update: restart backups, and try to resume recording.
 *  Android 14+ forbids starting the mic from boot; the service then raises a loud "tap to resume". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SyncWorker.schedulePeriodic(ctx)
        SyncWorker.kick(ctx)
        if (Prefs(ctx).wantRecording) {
            try {
                RecorderService.start(ctx)
            } catch (e: Exception) {
                Notif.alarm(ctx, Notif.ID_REC_STOPPED, "⚠ RECORDING IS OFF",
                    "Phone restarted. Tap here to resume recording.", resume = true)
            }
        }
    }
}
