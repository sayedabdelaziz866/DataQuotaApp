package com.dataquota.app

import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object WifiHelper {

    /**
     * Returns the BSSID (MAC address of the access point/router) of the
     * currently connected Wi-Fi network, or null if not connected to Wi-Fi
     * or if it couldn't be read (e.g. location permission not granted).
     */
    fun getCurrentBssid(context: Context): String? {
        if (!isConnectedToWifi(context)) return null

        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null

        val info = wifiManager.connectionInfo ?: return null
        val bssid = info.bssid

        // Android returns "02:00:00:00:00:00" when it can't/won't give the
        // real BSSID (usually a permissions issue) - treat that as unknown.
        if (bssid.isNullOrEmpty() || bssid == "02:00:00:00:00:00") return null
        return bssid
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

    /** True if we're currently on the Wi-Fi network the user registered as "home". */
    fun isOnHomeNetwork(context: Context, quotaManager: QuotaManager): Boolean {
        val home = quotaManager.getHomeBssid() ?: return false
        val current = getCurrentBssid(context) ?: return false
        return home.equals(current, ignoreCase = true)
    }
}
