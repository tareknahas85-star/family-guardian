package com.tarek.guardian.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tarek.guardian.BuildConfig
import com.tarek.guardian.Prefs
import com.tarek.guardian.R
import com.tarek.guardian.admin.GuardianDeviceAdminReceiver
import com.tarek.guardian.admin.PolicyManager
import com.tarek.guardian.calls.CallScreeningRole
import com.tarek.guardian.data.FirebaseRepo
import com.tarek.guardian.databinding.ActivityChildSetupBinding
import com.tarek.guardian.monitor.AppBlockService
import com.tarek.guardian.monitor.MonitorService
import com.tarek.guardian.vpn.FilterVpnService

class ChildSetupActivity : AppCompatActivity() {
    private lateinit var b: ActivityChildSetupBinding
    private lateinit var policyMgr: PolicyManager
    private lateinit var requestsPanel: ChildRequestsPanel

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startService(Intent(this, FilterVpnService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChildSetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        policyMgr = PolicyManager(this)

        b.tvVersion.text = getString(R.string.child_version, BuildConfig.VERSION_NAME)

        Prefs.getPairCode(this)?.let { b.etPairCode.setText(it) }

        requestsPanel = ChildRequestsPanel(this, b)
        requestsPanel.start()
        if (intent?.getBooleanExtra(ChildRequestsPanel.EXTRA_ASK_TIME, false) == true) {
            requestsPanel.askForTime()
        }

        b.btnPair.setOnClickListener {
            val code = b.etPairCode.text.toString().trim().uppercase()
            if (code.length < 4) {
                b.tvStatus.text = getString(R.string.pair_invalid_code)
                return@setOnClickListener
            }
            b.tvStatus.text = getString(R.string.pair_in_progress)
            b.btnPair.isEnabled = false
            // Join the code first; without membership every later write is rejected
            // by the database rules. The parent must have opened a pairing window.
            FirebaseRepo.claimDeviceDetailed(code) { ok, err ->
                runOnUiThread {
                    b.btnPair.isEnabled = true
                    if (!ok) {
                        b.tvStatus.text = getString(
                            R.string.pair_failed_child, pairingErrorText(this, err)
                        )
                        return@runOnUiThread
                    }
                    Prefs.setPairCode(this, code)
                    FirebaseRepo.setChildInfo(code, "${Build.MANUFACTURER} ${Build.MODEL}")
                    MonitorService.start(this)
                    b.tvStatus.text = getString(R.string.pair_done, code)
                    requestsPanel.attach()
                }
            }
        }

        b.btnPermUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        b.btnPermAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        b.btnPermLocation.setOnClickListener { requestRuntimePerms() }
        b.btnPermAdmin.setOnClickListener { requestAdmin() }
        b.btnStartVpn.setOnClickListener { prepareVpn() }
        b.btnAutostart.setOnClickListener { openAutostartSettings() }

        b.btnPermCallScreening.setOnClickListener {
            if (!CallScreeningRole.request(this)) {
                Toast.makeText(
                    this,
                    CallScreeningRole.statusText(this),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        b.btnSos.setOnClickListener { confirmSos() }
    }

    override fun onDestroy() {
        requestsPanel.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        requestsPanel.attach()
        b.tvCallScreeningStatus.text = getString(R.string.call_filtering_status, CallScreeningRole.statusText(this))
        b.tvProtectionStatus.text = protectionSummary()
        renderGrantStatus()
        // Re-assert the owner policies whenever this screen is opened; an OTA or a
        // policy reset can silently clear them.
        policyMgr.applyBaselineOwnerPolicies()

        // So the current pairing code can be compared, character for character,
        // against what the parent's app shows — the fastest way to catch a
        // mismatch between the two devices.
        val code = Prefs.getPairCode(this)
        if (!code.isNullOrBlank()) b.tvStatus.text = getString(R.string.pair_current, code)
    }

    /**
     * Colour-coded, always-visible status for the two one-time grants that
     * LOCK_NOW and BLOCK_INTERNET silently depend on (see
     * MonitorService.handleCommand). Checked fresh every time this screen is
     * shown, since either can be revoked outside the app (a device-admin
     * policy reset, the user turning the VPN off from Android Settings).
     */
    private fun renderGrantStatus() {
        val adminOk = policyMgr.isAdminActive
        b.tvAdminStatus.text = getString(if (adminOk) R.string.child_admin_ok else R.string.child_admin_bad)
        b.tvAdminStatus.setTextColor(getColor(if (adminOk) R.color.secondary_dark else R.color.error))

        // prepare() would disconnect an allowed external VPN (e.g. Proton) just
        // by opening this screen, so skip the check while one is running.
        val extVpn = FilterVpnService.allowExternalVpn && com.tarek.guardian.vpn.VpnState.otherVpnActive(this)
        val vpnOk = extVpn || VpnService.prepare(this) == null
        b.tvVpnStatus.text = getString(if (extVpn) R.string.dash_vpn_external else if (vpnOk) R.string.child_vpn_ok else R.string.child_vpn_bad)
        b.tvVpnStatus.setTextColor(getColor(if (vpnOk) R.color.secondary_dark else R.color.error))

        // Checked directly against Settings.Secure (see AppBlockService.isEnabled),
        // not an in-memory flag — the only reliable way to know app blocking,
        // whole-device lock, time limits and schedules can actually run.
        val accOk = AppBlockService.isEnabled(this)
        b.tvAccessibilityStatus.text = getString(if (accOk) R.string.child_acc_ok else R.string.child_acc_bad)
        b.tvAccessibilityStatus.setTextColor(getColor(if (accOk) R.color.secondary_dark else R.color.error))
    }

    /**
     * A short, honest summary of how hard the app is to remove — a parent should
     * not be left believing the child cannot uninstall it when they can.
     */
    private fun protectionSummary(): String = when {
        policyMgr.isDeviceOwner ->
            getString(R.string.protect_owner)
        policyMgr.isAdminActive ->
            getString(R.string.protect_admin)
        else ->
            getString(R.string.protect_none)
    }

    private fun confirmSos() {
        val code = Prefs.getPairCode(this)
        if (code.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.pair_first), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sos_confirm_title)
            .setMessage(R.string.sos_confirm_body)
            .setPositiveButton(R.string.send) { _, _ ->
                MonitorService.sendSos(this)
                Toast.makeText(this, getString(R.string.sos_sent), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Background location has to be asked for separately on Android 10+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                102
            )
        }
    }

    private fun requestAdmin() {
        val admin = ComponentName(this, GuardianDeviceAdminReceiver::class.java)
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_explanation)
            )
        startActivity(i)
    }

    private fun prepareVpn() {
        val prep = VpnService.prepare(this)
        if (prep != null) vpnLauncher.launch(prep)
        else startService(Intent(this, FilterVpnService::class.java))
    }

    /**
     * The one Android permission dialog does NOT cover.
     *
     * `requestNoDoze()`-style battery exemption (standard AOSP Doze) only
     * protects against stock Android. Huawei, Xiaomi, Oppo, Vivo and a few
     * others ship a second, manufacturer-specific process killer on top of
     * stock Android that a normal battery-optimisation exemption does not
     * touch. It silently freezes background network connections — including
     * the always-open Firebase listener LOCK_NOW/BLOCK_INTERNET/camera-disable
     * depend on — even while this app's foreground notification stays visible
     * and its own periodic writes (location, usage, "last seen") keep
     * succeeding, because those are short bursts the OS lets through. That
     * combination — outbound reports keep working, nothing sent in ever
     * arrives — is the exact symptom this button exists to fix.
     *
     * There is no public API for any of this, so every manufacturer's screen
     * is reached the same unreliable way everyone else does it: try each
     * known component in turn, first match wins, fall back to the app's own
     * battery-optimisation dialog if none of them resolve on this device.
     */
    private fun openAutostartSettings() {
        val candidates = listOf(
            // Huawei / Honor — "Protected apps" and "App launch"
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            // Xiaomi (MIUI) — autostart manager
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Oppo / Realme (ColorOS)
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Vivo (FuntouchOS)
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            // Samsung — sleeping/deep-sleeping apps list
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            // Asus
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
        )

        for (c in candidates) {
            try {
                startActivity(Intent().apply {
                    component = c
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            } catch (_: Exception) {
                // Not this manufacturer, or this EMUI/MIUI/ColorOS version moved the
                // screen — try the next one.
            }
        }

        // Nothing manufacturer-specific resolved: fall back to the one exemption
        // Android itself guarantees a path to.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                getString(R.string.autostart_fallback),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
