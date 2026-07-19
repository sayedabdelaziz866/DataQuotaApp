package com.dataquota.app

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * A backup safety net, independent of UsageMonitorService and BootReceiver.
 * WorkManager reschedules its own jobs after a reboot automatically (this
 * doesn't depend on our BootReceiver working correctly on every OEM), so
 * even if the real-time foreground service fails to restart after a reboot
 * or gets killed by aggressive OEM battery management, this worker will
 * still catch it and re-block within its interval.
 *
 * 15 minutes is the shortest interval Android's WorkManager allows for
 * periodic work - this is a backup net, not the primary enforcement (that's
 * still UsageMonitorService's 5-second loop).
 */
class QuotaCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "quota_backup_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuotaCheckWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val quotaManager = QuotaManager(applicationContext)
        if (!quotaManager.isMonitoringEnabled()) return Result.success()

        // Make sure the real-time monitor service is actually alive too -
        // this is what recovers it after a reboot even if BootReceiver
        // didn't fire for whatever OEM-specific reason.
        applicationContext.startForegroundService(
            Intent(applicationContext, UsageMonitorService::class.java)
        )

        if (quotaManager.isOverLimit()) {
            quotaManager.setBlocked(true)
            val intent = Intent(applicationContext, QuotaVpnService::class.java).apply {
                action = QuotaVpnService.ACTION_START
            }
            applicationContext.startService(intent)
        }

        return Result.success()
    }
}
