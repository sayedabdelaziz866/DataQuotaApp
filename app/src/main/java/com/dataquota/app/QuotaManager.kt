package com.dataquota.app

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Central place for reading/writing quota state.
 * Everything is stored in SharedPreferences for now (simple, no DB needed
 * for a single-device single-limit app).
 */
class QuotaManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("quota_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HOME_BSSIDS = "home_bssids"       // now a Set<String>
        private const val KEY_HOME_SSID = "home_ssid"           // just for display
        private const val KEY_HOME_GATEWAY = "home_gateway_ip"  // fallback that needs no Location permission
        private const val KEY_LIMIT_BYTES = "limit_bytes"
        private const val KEY_USED_BYTES = "used_bytes"
        private const val KEY_LAST_WIFI_RX = "last_wifi_rx"
        private const val KEY_LAST_WIFI_TX = "last_wifi_tx"
        private const val KEY_CYCLE_MONTH = "cycle_month"       // which month the counter belongs to
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_BLOCKED = "is_blocked"
        private const val KEY_WARNED_80 = "warned_80_percent"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val KEY_LAST_BOOT_RECEIVER = "last_boot_receiver_fired"
        private const val KEY_LAST_BOOT_ERROR = "last_boot_receiver_error"
    }

    // ---------- Home network ----------

    /** Registers the currently-connected network (auto-detected BSSID + gateway). */
    fun setHomeNetwork(bssid: String?, ssid: String, gatewayIp: String?) {
        val editor = prefs.edit().putString(KEY_HOME_SSID, ssid)
        if (bssid != null) {
            val current = getHomeBssids().toMutableSet()
            current.add(bssid.uppercase())
            editor.putStringSet(KEY_HOME_BSSIDS, current)
        }
        if (gatewayIp != null) editor.putString(KEY_HOME_GATEWAY, gatewayIp)
        editor.apply()
    }

    /** Adds one BSSID typed in manually (e.g. copied from a Wi-Fi analyzer app),
     *  without needing to be connected to that network right now. Useful for
     *  registering a router's second band (2.4GHz/5GHz) ahead of time. */
    fun addManualBssid(bssid: String) {
        val current = getHomeBssids().toMutableSet()
        current.add(bssid.trim().uppercase())
        prefs.edit().putStringSet(KEY_HOME_BSSIDS, current).apply()
    }

    fun getHomeBssids(): Set<String> = prefs.getStringSet(KEY_HOME_BSSIDS, emptySet()) ?: emptySet()
    fun getHomeSsid(): String? = prefs.getString(KEY_HOME_SSID, null)
    fun getHomeGatewayIp(): String? = prefs.getString(KEY_HOME_GATEWAY, null)

    // ---------- Limit ----------

    fun setLimitBytes(bytes: Long) {
        prefs.edit().putLong(KEY_LIMIT_BYTES, bytes)
            .putBoolean(KEY_WARNED_80, false)
            .apply()
    }

    fun getLimitBytes(): Long = prefs.getLong(KEY_LIMIT_BYTES, 0L)

    // ---------- Usage ----------

    fun getUsedBytes(): Long {
        resetIfNewMonth()
        return prefs.getLong(KEY_USED_BYTES, 0L)
    }

    fun addUsedBytes(delta: Long) {
        if (delta <= 0) return
        resetIfNewMonth()
        val newTotal = getUsedBytes() + delta
        prefs.edit().putLong(KEY_USED_BYTES, newTotal).apply()
        addDailyBytes(delta)
    }

    // ---------- Daily breakdown (for the chart) ----------

    private fun dailyKey(dateStr: String) = "daily_$dateStr"

    private fun addDailyBytes(delta: Long) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val key = dailyKey(today)
        val current = prefs.getLong(key, 0L)
        prefs.edit().putLong(key, current + delta).apply()
    }

    /** Returns (date label, bytes) for each of the last [days] days, oldest first. */
    fun getDailyUsage(days: Int): List<Pair<String, Long>> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val labelFmt = java.text.SimpleDateFormat("d/M", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        val result = mutableListOf<Pair<String, Long>>()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
        repeat(days) {
            val dateStr = fmt.format(cal.time)
            val label = labelFmt.format(cal.time)
            val bytes = prefs.getLong(dailyKey(dateStr), 0L)
            result.add(Pair(label, bytes))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    fun isOverLimit(): Boolean {
        val limit = getLimitBytes()
        if (limit <= 0) return false // no limit configured yet
        return getUsedBytes() >= limit
    }

    /** Manual reset, e.g. user taps "Reset usage" button. */
    fun resetUsage() {
        prefs.edit()
            .putLong(KEY_USED_BYTES, 0L)
            .putInt(KEY_CYCLE_MONTH, currentMonthKey())
            .putBoolean(KEY_WARNED_80, false)
            .apply()
    }

    private fun resetIfNewMonth() {
        val stored = prefs.getInt(KEY_CYCLE_MONTH, -1)
        val current = currentMonthKey()
        if (stored != current) {
            prefs.edit()
                .putLong(KEY_USED_BYTES, 0L)
                .putInt(KEY_CYCLE_MONTH, current)
                .putBoolean(KEY_WARNED_80, false)
                .apply()
        }
    }

    fun hasWarned80(): Boolean = prefs.getBoolean(KEY_WARNED_80, false)
    fun setWarned80(warned: Boolean) {
        prefs.edit().putBoolean(KEY_WARNED_80, warned).apply()
    }

    // ---------- Diagnostics ----------

    /** Timestamp of the last time the background monitor loop actually ran.
     *  If this stops updating, the service got frozen/killed by the OS -
     *  useful for telling "logic bug" apart from "OEM killed the process". */
    fun setLastCheckTime(millis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, millis).apply()
    }

    fun getLastCheckTime(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    /** Set the instant BootReceiver.onReceive() is entered - separate from
     *  the regular check timestamp, so we can tell "the OS never delivered
     *  the boot broadcast to us" apart from "it fired but something after
     *  that failed". */
    fun setLastBootReceiverFired(millis: Long) {
        prefs.edit().putLong(KEY_LAST_BOOT_RECEIVER, millis).apply()
    }

    fun getLastBootReceiverFired(): Long = prefs.getLong(KEY_LAST_BOOT_RECEIVER, 0L)

    fun setLastBootReceiverError(message: String) {
        prefs.edit().putString(KEY_LAST_BOOT_ERROR, message).apply()
    }

    fun getLastBootReceiverError(): String? = prefs.getString(KEY_LAST_BOOT_ERROR, null)

    private fun currentMonthKey(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
    }

    // ---------- Snapshot for delta calculation (TrafficStats is cumulative since boot) ----------

    fun getLastWifiSnapshot(): Pair<Long, Long> {
        return Pair(
            prefs.getLong(KEY_LAST_WIFI_RX, -1L),
            prefs.getLong(KEY_LAST_WIFI_TX, -1L)
        )
    }

    fun saveWifiSnapshot(rx: Long, tx: Long) {
        prefs.edit().putLong(KEY_LAST_WIFI_RX, rx).putLong(KEY_LAST_WIFI_TX, tx).apply()
    }

    // ---------- Monitoring / blocking state ----------

    fun setMonitoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
    }

    fun isMonitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITORING_ENABLED, false)

    fun setBlocked(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCKED, blocked).apply()
    }

    fun isBlocked(): Boolean = prefs.getBoolean(KEY_BLOCKED, false)
}
