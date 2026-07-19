package com.dataquota.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * This is our Device Policy Controller (DPC). When the device is
 * provisioned via the QR-code flow during initial setup (right after a
 * factory reset), Android installs this app, sets it as Device Owner, and
 * then calls onProfileProvisioningComplete() below - that's our one chance
 * to finish setup: launch the app and lock down uninstall protection.
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

        // We are now Device Owner - this alone already gives the app more
        // reliable background execution after reboot (bypasses a lot of
        // OEM battery-management interference). We do NOT auto-block
        // uninstall here on purpose: the app stays normally removable
        // unless the person explicitly enables that from the app's screen.
        dpm.setProfileEnabled(adminComponent)

        // Bring the app to the foreground so the person can finish setup
        // (register the home network, set the limit, start monitoring).
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }

    companion object {
        /** Call this any time (e.g. from a button in MainActivity) to
         *  (re)apply the uninstall-block, in case it wasn't set during
         *  provisioning for any reason. Only works if this app is already
         *  Device Owner - it's a no-op / throws otherwise. */
        fun applyProtections(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return

            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
        }

        /** Removes the uninstall-block, so the app goes back to being a
         *  normal, freely-removable app (while staying Device Owner, which
         *  still helps it survive reboots more reliably). */
        fun removeUninstallProtection(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return

            dpm.setUninstallBlocked(adminComponent, context.packageName, false)
        }

        fun isUninstallBlocked(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            return dpm.isUninstallBlocked(adminComponent, context.packageName)
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        /** Device-Owner-exclusive: grants this app persistent, system-level
         *  VPN authorization that doesn't need the normal one-time
         *  interactive consent dialog - and, unlike that consent, this is
         *  designed by Android to reliably survive reboots. lockdownEnabled
         *  is false so we still fully control when the tunnel actually
         *  blocks traffic (see QuotaVpnService), rather than forcing the
         *  VPN to always be connected. */
        fun enableAlwaysOnVpnAuthorization(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            return try {
                dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, false)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun isAlwaysOnVpnAuthorized(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            return try {
                dpm.getAlwaysOnVpnPackage(adminComponent) == context.packageName
            } catch (e: Exception) {
                false
            }
        }
    }
}
