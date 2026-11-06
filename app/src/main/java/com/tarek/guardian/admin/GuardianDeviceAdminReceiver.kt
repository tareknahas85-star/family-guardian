package com.tarek.guardian.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        PolicyManager(context).applyBaselineOwnerPolicies()
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        PolicyManager(context).applyBaselineOwnerPolicies()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Shown if someone tries to deactivate admin. Uninstall is separately blocked
        // by Device Owner, so this is a second line of defense. The parent hears
        // about the attempt right away, whether or not it goes through.
        com.tarek.guardian.alerts.TamperReporter.report(context, com.tarek.guardian.R.string.tamper_admin_attempt)
        return com.tarek.guardian.L10n.s(com.tarek.guardian.R.string.admin_disable_warning)
    }
}
