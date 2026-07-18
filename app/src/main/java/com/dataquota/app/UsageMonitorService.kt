package com.dataquota.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs continuously while monitoring is enabled.
 * Every CHECK_INTERVAL_MS it:
 *   1. Checks whether we're currently on the registered "home" Wi-Fi.
 *   2. If yes, measures how many Wi-Fi bytes were used since the last
 *      check and adds that delta to the stored total.
 *   3. If the total now exceeds the configured limit, starts the
 *      blackhole VPN (QuotaVpnService) to cut internet.
 *   4. If usage is back under the limit (e.g. after a manual reset),
 *      makes sure the VPN is stopped.
 *
 * We use a plain Handler loop instead of WorkManager because WorkManager's
 * minimum periodic interval is 15 minutes, which is far too slow for a
 * "cut internet immediately" requirement.
 */
class UsageMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var quotaManager: QuotaManager

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkUsageOnce()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 15_000L // check every 15 seconds
        private const val NOTIF_CHANNEL_ID = "usage_monitor_channel"
        private const val NOTIF_ID = 7
        private const val WARNING_CHANNEL_ID = "quota_warning_channel"
        private const val WARNING_NOTIF_ID = 8
    }

    override fun onCreate() {
        super.onCreate()
        quotaManager = QuotaManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        handler.removeCallbacks(checkRunnable)
        handler.post(checkRunnable)
        return START_STICKY
    }

    private fun checkUsageOnce() {
        quotaManager.setLastCheckTime(System.currentTimeMillis())
        val onHome = WifiHelper.isOnHomeNetwork(this, quotaManager)

        if (onHome) {
            accumulateWifiUsage()
        } else {
            // Not on the home network: don't count usage, and make sure
            // we're not blocking - the block only applies to the home network.
            resetSnapshotBaseline()
            if (quotaManager.isBlocked()) {
                stopBlockingVpn()
            }
            return
        }

        if (quotaManager.isOverLimit()) {
            if (!quotaManager.isBlocked()) {
                startBlockingVpn()
            }
        } else {
            if (quotaManager.isBlocked()) {
                stopBlockingVpn()
            }
            checkApproachingLimit()
        }
    }

    /** Sends a one-time heads-up notification once usage crosses 80% of the limit. */
    private fun checkApproachingLimit() {
        val limit = quotaManager.getLimitBytes()
        if (limit <= 0) return
        val used = quotaManager.getUsedBytes()
        if (used >= limit * 0.8 && !quotaManager.hasWarned80()) {
            quotaManager.setWarned80(true)
            sendWarningNotification(used, limit)
        }
    }

    private fun sendWarningNotification(used: Long, limit: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WARNING_CHANNEL_ID,
                "Data Quota Warning",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
        val usedGb = used / 1024.0 / 1024.0 / 1024.0
        val limitGb = limit / 1024.0 / 1024.0 / 1024.0
        val notification = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setContentTitle("قربت تخلص الجيجات")
            .setContentText(String.format("استهلكت %.2f GB من %.2f GB - على وشك الوصول للحد", usedGb, limitGb))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(WARNING_NOTIF_ID, notification)

    /**
     * TrafficStats totals are cumulative since the last device boot, and
     * cover ALL networks. We isolate Wi-Fi-only bytes by subtracting the
     * mobile-data totals, then diff against our last saved snapshot to
     * get "how much changed since last check".
     */
    private fun accumulateWifiUsage() {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()

        if (totalRx == TrafficStats.UNSUPPORTED.toLong()) return // device doesn't support this

        val wifiRx = totalRx - mobileRx
        val wifiTx = totalTx - mobileTx

        val (lastRx, lastTx) = quotaManager.getLastWifiSnapshot()

        if (lastRx < 0 || lastTx < 0) {
            // First run (or just switched onto home network) - just save
            // the baseline, nothing to add yet.
            quotaManager.saveWifiSnapshot(wifiRx, wifiTx)
            return
        }

        // Guard against counter resets (e.g. device rebooted, counters
        // went back to near zero) - if it looks negative, just re-baseline.
        val deltaRx = wifiRx - lastRx
        val deltaTx = wifiTx - lastTx
        if (deltaRx < 0 || deltaTx < 0) {
            quotaManager.saveWifiSnapshot(wifiRx, wifiTx)
            return
        }

        quotaManager.addUsedBytes(deltaRx + deltaTx)
        quotaManager.saveWifiSnapshot(wifiRx, wifiTx)
    }

    private fun resetSnapshotBaseline() {
        quotaManager.saveWifiSnapshot(-1L, -1L)
    }

    private fun startBlockingVpn() {
        quotaManager.setBlocked(true)
        val intent = Intent(this, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopBlockingVpn() {
        quotaManager.setBlocked(false)
        val intent = Intent(this, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Data Quota Monitoring",
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("مراقبة استهلاك الإنترنت شغالة")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
