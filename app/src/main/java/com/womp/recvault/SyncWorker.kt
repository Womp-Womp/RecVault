package com.womp.recvault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SyncEngine.runOnce(applicationContext)
        return Result.success()
    }

    companion object {
        /** Every 15 minutes (Android's minimum), 4x more often than the hourly promise.
         *  No network constraint on purpose: the watchdog must run offline too. */
        fun schedulePeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "rv-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /** Right now (e.g. a chunk just finished). Queued behind any running pass. */
        fun kick(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "rv-kick", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
        }
    }
}
