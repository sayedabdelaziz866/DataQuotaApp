package com.dataquota.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream

/**
 * This VPN does NOT forward traffic anywhere. It exists purely as an
 * internet "kill switch": once Android routes all traffic into our TUN
 * interface, and we simply never read+forward it onward, every app on
 * the device effectively loses internet access - even though Wi-Fi still
 * shows as "connected" in the status bar.
 *
 * We start this service only when the configured data quota is exceeded,
 * and stop it again when the user resets usage or raises the limit.
 */
class QuotaVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var blackholeThread: Thread? = null
    @Volatile private var running = false

    companion object {
        const val ACTION_START = "com.dataquota.app.action.START_BLOCK"
        const val ACTION_STOP = "com.dataquota.app.action.STOP_BLOCK"
        private const val NOTIF_CHANNEL_ID = "quota_vpn_channel"
        private const val NOTIF_ID = 42
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBlocking()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startBlocking()
        }
        return START_STICKY
    }

    private fun startBlocking() {
        // Always tear down and re-establish, even if we think we're already
        // running - a Wi-Fi toggle silently kills the old tunnel while
        // leaving our 'running' flag stale, so trusting it would leave the
        // device unblocked until the next full restart.
        teardownInterface()

        val builder = Builder()
            .setSession("Data Quota - Blocked")
            .addAddress("10.10.10.2", 32)
            // Routing everything (0.0.0.0/0) into the tun is what actually
            // stops traffic from reaching the real network.
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.10.10.1")

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            // No active network right now (e.g. Wi-Fi mid-reconnect) -
            // the next periodic check from UsageMonitorService will retry.
            return
        }
        running = true

        startForeground(NOTIF_ID, buildNotification())

        // Drain the tun's read side so it doesn't fill up and block/crash;
        // we intentionally do nothing with the bytes we read (blackhole).
        blackholeThread = Thread {
            try {
                val input = FileInputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteArray(32767)
                while (running) {
                    // Blocks until a packet arrives; we just discard it.
                    if (input.read(buffer) < 0) break
                }
            } catch (e: Exception) {
                // Interface was torn down - expected when stopBlocking() runs.
            }
        }
        blackholeThread?.start()
    }

    private fun teardownInterface() {
        running = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // ignore
        }
        vpnInterface = null
        blackholeThread = null
    }

    private fun stopBlocking() {
        teardownInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Data Quota Blocking",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("تم الوصول للحد المسموح")
            .setContentText("تم قطع الإنترنت عن هذا الجهاز حتى إعادة التعيين")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopBlocking()
        super.onDestroy()
    }

    override fun onRevoke() {
        // User manually disconnected the VPN from Android's VPN settings.
        stopBlocking()
        super.onRevoke()
    }
}
