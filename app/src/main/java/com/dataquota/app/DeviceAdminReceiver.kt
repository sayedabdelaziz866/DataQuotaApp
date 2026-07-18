package com.dataquota.app

import android.content.Context
import android.content.Intent

/**
 * Placeholder for Phase 2 (MDM / uninstall protection).
 * Registering this as a basic Device Admin already makes the app harder
 * to uninstall (Android shows a warning and requires deactivating admin
 * first), but the strong version - full Device Owner provisioning that
 * blocks uninstall entirely - needs a separate enrollment flow (QR code
 * during factory reset) that we'll wire up once the core app is working.
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
