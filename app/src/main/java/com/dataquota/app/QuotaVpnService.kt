package com.dataquota.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    @Volatile private var shouldBeBlocking = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val ACTION_START = "com.dataquota.app.action.START_BLOCK"
        const val ACTION_STOP = "com.dataquota.app.action.STOP_BLOCK"
        private const val NOTIF_CHANNEL_ID = "quota_vpn_channel"
        private const val NOTIF_ID = 42
    }

    override fun onCreate() {
        super.onCreate()
        registerNetworkWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shouldBeBlocking = false
                stopBlocking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                shouldBeBlocking = true
                startBlocking()
            }
            else -> {
                // Android's "Always-on VPN" mechanism can launch us
                // automatically (e.g. right after boot, or on a network
                // change) without our own ACTION_START. Only actually
                // block if the quota is genuinely still exceeded - this
                // matters because this same auto-launch also gives us the
                // persistent, reboot-proof authorization we need.
                val quotaManager = QuotaManager(this)
                if (quotaManager.isOverLimit()) {
                    shouldBeBlocking = true
                    startBlocking()
                }
            }
        }
        return START_STICKY
    }

    /**
     * Watches for the underlying network changing (e.g. Wi-Fi turned off
     * then back on). When that happens our TUN tunnel is silently killed by
     * the OS; this is how we notice and rebuild it - instead of blindly
     * tearing down and rebuilding on every single periodic check, which
     * caused a visible flicker (internet briefly working, every few
     * seconds, during each rebuild).
     */
    private fun registerNetworkWatcher() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (shouldBeBlocking) {
                    startBlocking()
                }
            }
        }
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            // Some OEMs restrict this - the periodic re-check from
            // UsageMonitorService (every 5s while over limit) is still a
            // fallback, just less instant.
        }
    }

    private fun startBlocking() {
        // Only rebuild if we're not already running - avoids tearing the
        // tunnel down and recreating it unnecessarily. We only actually
        // reach here again after a real network change (see
        // registerNetworkWatcher) or an explicit ACTION_START.
        if (running) return

        val quotaManager = QuotaManager(this)

        // Elevate to foreground FIRST, before attempting establish(). Doing
        // this the other way around (which the code used to do) meant
        // establish() was being called while still a plain background
        // service - Android can silently refuse to hand out a VPN
        // interface in that state, especially in the first moments after
        // boot, which is exactly the failure we were seeing.
        startForeground(NOTIF_ID, buildNotification())

        val builder = Builder()
            .setSession("Data Quota - Blocked")
            .addAddress("10.10.10.2", 32)
            // Routing everything (0.0.0.0/0) into the tun is what actually
            // stops traffic from reaching the real network.
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.10.10.1")

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            quotaManager.setVpnActuallyEstablished(false)
            quotaManager.setVpnEstablishError(e.toString())
            null
        }

        if (vpnInterface == null) {
            // No active network right now (e.g. Wi-Fi mid-reconnect), OR
            // VPN consent isn't currently granted - either way, record the
            // real failure so the UI doesn't lie about being blocked.
            quotaManager.setVpnActuallyEstablished(false)
            if (quotaManager.getVpnEstablishError() == null) {
                quotaManager.setVpnEstablishError("establish() returned null (no exception thrown)")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        running = true
        quotaManager.setVpnActuallyEstablished(true)
        quotaManager.setVpnEstablishError(null)

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
        QuotaManager(this).setVpnActuallyEstablished(false)
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
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // ignore
        }
        stopBlocking()
        super.onDestroy()
    }

    override fun onRevoke() {
        // User manually disconnected the VPN from Android's VPN settings.
        stopBlocking()
        super.onRevoke()
    }
}
