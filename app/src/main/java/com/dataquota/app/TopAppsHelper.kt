package com.dataquota.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings

/**
 * Reads how much Wi-Fi data each installed app has used, using
 * NetworkStatsManager. This needs the special "Usage Access" permission,
 * which - unlike Location/Notifications - can't be requested with a normal
 * runtime permission dialog. The user has to flip it on manually from a
 * system settings screen; openUsageAccessSettings() takes them there.
 */
object TopAppsHelper {

    data class AppUsage(val appName: String, val bytes: Long)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Top Wi-Fi-consuming apps since [sinceMillis], highest first. */
    @Suppress("DEPRECATION")
    fun getTopApps(context: Context, sinceMillis: Long, limit: Int = 5): List<AppUsage> {
        if (!hasUsageAccess(context)) return emptyList()

        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyList()
        val pm = context.packageManager
        val usageByUid = mutableMapOf<Int, Long>()

        try {
            val bucket = NetworkStats.Bucket()
            val stats = nsm.querySummary(
                ConnectivityManager.TYPE_WIFI,
                null,
                sinceMillis,
                System.currentTimeMillis()
            )
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val total = bucket.rxBytes + bucket.txBytes
                usageByUid[bucket.uid] = (usageByUid[bucket.uid] ?: 0L) + total
            }
            stats.close()
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        return usageByUid.mapNotNull { (uid, bytes) ->
            val pkgName = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull null
            val appName = try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                pkgName
            }
            AppUsage(appName, bytes)
        }
            .filter { it.bytes > 0 }
            .sortedByDescending { it.bytes }
            .take(limit)
    }
}
