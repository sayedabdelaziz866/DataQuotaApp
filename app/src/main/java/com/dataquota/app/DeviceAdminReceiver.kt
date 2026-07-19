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

        // We are now Device Owner. Activate the managed profile/device and
        // immediately block uninstalling this app - this is the actual
        // "can't be removed by the kid" protection.
        dpm.setProfileEnabled(adminComponent)
        applyProtections(context)

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

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
    }
}
