package com.dataquota.app

import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object WifiHelper {

    /**
     * Returns the BSSID (MAC address of the access point/router) of the
     * currently connected Wi-Fi network, or null if not connected to Wi-Fi
     * or if it couldn't be read (needs Location permission + Location
     * services turned on - see getCurrentGatewayIp() for a fallback that
     * doesn't need Location at all).
     */
    fun getCurrentBssid(context: Context): String? {
        if (!isConnectedToWifi(context)) return null

        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null

        val info = wifiManager.connectionInfo ?: return null
        val bssid = info.bssid

        // Android returns "02:00:00:00:00:00" when it can't/won't give the
        // real BSSID (Location permission missing OR Location services
        // turned off system-wide) - treat that as unknown.
        if (bssid.isNullOrEmpty() || bssid == "02:00:00:00:00:00") return null
        return bssid
    }

    /**
     * Returns the router's LAN IP address (the DHCP default gateway),
     * e.g. "192.168.1.1". This comes from plain DHCP lease info and does
     * NOT require Location permission or Location services to be on, so
     * it keeps working even if the BSSID lookup is blocked.
     *
     * It's a weaker identifier than BSSID (two different routers could
     * both use 192.168.1.1 as their default), so we use BSSID first when
     * available and only fall back to this.
     */
    @Suppress("DEPRECATION")
    fun getCurrentGatewayIp(context: Context): String? {
        if (!isConnectedToWifi(context)) return null
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
        val gatewayInt = wifiManager.dhcpInfo?.gateway ?: return null
        if (gatewayInt == 0) return null
        return intToIp(gatewayInt)
    }

    private fun intToIp(value: Int): String {
        return "${value and 0xFF}.${value shr 8 and 0xFF}.${value shr 16 and 0xFF}.${value shr 24 and 0xFF}"
    }

    fun getCurrentSsid(context: Context): String? {
        if (!isConnectedToWifi(context)) return null
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
        val ssid = wifiManager.connectionInfo?.ssid ?: return null
        // SSID often comes wrapped in quotes, e.g. "\"MyNetwork\""
        return ssid.trim('"')
    }

    fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * True if we're currently on the Wi-Fi network the user registered as
     * "home". Prefers the precise BSSID match; if BSSID can't be read right
     * now (e.g. Location was turned off), falls back to comparing the
     * router's gateway IP instead of failing open or closed blindly.
     */
    fun isOnHomeNetwork(context: Context, quotaManager: QuotaManager): Boolean {
        val homeBssids = quotaManager.getHomeBssids()
        val homeGateway = quotaManager.getHomeGatewayIp()
        if (homeBssids.isEmpty() && homeGateway == null) return false

        val currentBssid = getCurrentBssid(context)
        if (currentBssid != null && homeBssids.isNotEmpty()) {
            return homeBssids.contains(currentBssid.uppercase())
        }

        // BSSID unavailable right now - fall back to gateway IP, which
        // doesn't depend on Location permission/services.
        val currentGateway = getCurrentGatewayIp(context)
        if (currentGateway != null && homeGateway != null) {
            return homeGateway == currentGateway
        }

        return false
    }
}

