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
        private const val KEY_HOME_BSSID = "home_bssid"
        private const val KEY_HOME_SSID = "home_ssid"           // just for display
        private const val KEY_LIMIT_BYTES = "limit_bytes"
        private const val KEY_USED_BYTES = "used_bytes"
        private const val KEY_LAST_WIFI_RX = "last_wifi_rx"
        private const val KEY_LAST_WIFI_TX = "last_wifi_tx"
        private const val KEY_CYCLE_MONTH = "cycle_month"       // which month the counter belongs to
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_BLOCKED = "is_blocked"
    }

    // ---------- Home network ----------

    fun setHomeNetwork(bssid: String, ssid: String) {
        prefs.edit().putString(KEY_HOME_BSSID, bssid)
            .putString(KEY_HOME_SSID, ssid)
            .apply()
    }

    fun getHomeBssid(): String? = prefs.getString(KEY_HOME_BSSID, null)
    fun getHomeSsid(): String? = prefs.getString(KEY_HOME_SSID, null)

    // ---------- Limit ----------

    fun setLimitBytes(bytes: Long) {
        prefs.edit().putLong(KEY_LIMIT_BYTES, bytes).apply()
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
            .apply()
    }

    private fun resetIfNewMonth() {
        val stored = prefs.getInt(KEY_CYCLE_MONTH, -1)
        val current = currentMonthKey()
        if (stored != current) {
            prefs.edit()
                .putLong(KEY_USED_BYTES, 0L)
                .putInt(KEY_CYCLE_MONTH, current)
                .apply()
        }
    }

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
