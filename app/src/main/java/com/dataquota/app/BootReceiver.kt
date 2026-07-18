package com.dataquota.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val quotaManager = QuotaManager(context)
        if (quotaManager.isMonitoringEnabled()) {
            val serviceIntent = Intent(context, UsageMonitorService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
