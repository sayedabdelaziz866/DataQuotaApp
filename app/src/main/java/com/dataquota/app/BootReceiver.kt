package com.dataquota.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Record this FIRST, unconditionally, before any other logic -
        // this is our proof of whether the OS delivered the broadcast to
        // us at all, independent of everything else that follows.
        val quotaManager = QuotaManager(context)
        quotaManager.setLastBootReceiverFired(System.currentTimeMillis())

        // Fires both after a device reboot AND after this app gets updated
        // (Android restarts the process on update, which silently drops
        // any Service that isn't explicitly restarted).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (quotaManager.isMonitoringEnabled()) {
            try {
                val serviceIntent = Intent(context, UsageMonitorService::class.java)
                context.startForegroundService(serviceIntent)
                QuotaCheckWorker.schedule(context)
            } catch (e: Exception) {
                quotaManager.setLastBootReceiverError(e.toString())
            }
        }
    }
}
