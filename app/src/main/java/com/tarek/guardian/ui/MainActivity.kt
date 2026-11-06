package com.tarek.guardian.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.tarek.guardian.BuildConfig
import com.tarek.guardian.Prefs
import com.tarek.guardian.R
import com.tarek.guardian.alerts.AlertNotifier
import com.tarek.guardian.alerts.ParentAlertService
import com.tarek.guardian.data.Command
import com.tarek.guardian.data.FirebaseRepo
import com.tarek.guardian.databinding.ActivityMainBinding
import com.tarek.guardian.ui.tabs.ActivityTabFragment
import com.tarek.guardian.ui.tabs.DashboardFragment
import com.tarek.guardian.ui.tabs.LiveFragment
import com.tarek.guardian.ui.tabs.SettingsTabFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The parent app's single host activity.
 *
 * The app used to be one long scrolling screen, which is why it "only showed one
 * page". Everything now lives in four tabs, following the Family Guardian navigation
 * spec: Dashboard, Activity, Live, Settings. The Kids tab was folded into the
 * dashboard — it duplicated what was already there.
 *
 * This activity owns all the Firebase listeners and publishes into
 * [GuardianState]; the tabs are pure renderers. Attaching listeners per tab meant
 * a reload on every tab switch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var code: String

    private var attachedAt = 0L
    private val alerts = ArrayList<com.tarek.guardian.data.Alert>()
    private val events = ArrayList<com.tarek.guardian.data.ActivityEvent>()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshHeader() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        b.bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    com.tarek.guardian.R.id.tab_activity -> ActivityTabFragment()
                    com.tarek.guardian.R.id.tab_live     -> LiveFragment()
                    com.tarek.guardian.R.id.tab_settings -> SettingsTabFragment()
                    else -> DashboardFragment()
                }
            )
            true
        }
        if (savedInstanceState == null) {
            b.bottomNav.selectedItemId = com.tarek.guardian.R.id.tab_dashboard
        }

        b.tvBell.setOnClickListener { openNotificationSettings() }

        val saved = Prefs.getPairCode(this)
        if (saved != null) startWithCode(saved) else chooseFamilySetup()
    }

    /**
     * First run on a parent phone: start a new family (this phone creates and
     * owns the code), or join the family the other parent already set up.
     */
    private fun chooseFamilySetup() {
        b.tvHeaderSub.text = getString(R.string.code_creating)
        AlertDialog.Builder(this)
            .setTitle(R.string.family_setup_title)
            .setItems(arrayOf(getString(R.string.family_new), getString(R.string.family_join))) { _, which ->
                if (which == 0) createOwnedCode() else joinExistingFamily()
            }
            .setCancelable(false)
            .show()
    }

    /** Second parent: joins with the code shown on the first parent's phone. */
    private fun joinExistingFamily() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.pair_code)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.family_join)
            .setMessage(R.string.family_join_hint)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.pair_device) { _, _ ->
                val c = input.text.toString().trim().uppercase()
                if (c.length < 4) { chooseFamilySetup(); return@setPositiveButton }
                FirebaseRepo.claimDeviceDetailed(c) { ok, err ->
                    if (isFinishing) return@claimDeviceDetailed
                    if (!ok) {
                        AlertDialog.Builder(this)
                            .setTitle("✗")
                            .setMessage(getString(R.string.pair_failed_child, pairingErrorText(this, err)))
                            .setCancelable(false)
                            .setPositiveButton(R.string.retry) { _, _ -> chooseFamilySetup() }
                            .show()
                        return@claimDeviceDetailed
                    }
                    Prefs.setPairCode(this, c)
                    startWithCode(c)
                    Toast.makeText(this, getString(R.string.family_joined), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> chooseFamilySetup() }
            .show()
    }

    /**
     * First run: the code is made on the server (owned by this phone) BEFORE it
     * is shown, so the child can never claim it first and lock the parent out.
     */
    private fun createOwnedCode() {
        b.tvHeaderSub.text = getString(R.string.code_creating)
        FirebaseRepo.createCode { newCode, err ->
            if (isFinishing) return@createCode
            if (newCode == null) {
                AlertDialog.Builder(this)
                    .setTitle("✗")
                    .setMessage(getString(R.string.code_create_failed, pairingErrorText(this, err)))
                    .setCancelable(false)
                    .setPositiveButton(R.string.retry) { _, _ -> createOwnedCode() }
                    .show()
                return@createCode
            }
            Prefs.setPairCode(this, newCode)
            startWithCode(newCode)
            AlertDialog.Builder(this)
                .setTitle("✓")
                .setMessage(getString(R.string.pair_window_open, newCode))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun startWithCode(c: String) {
        code = c
        GuardianState.update { it.copy(code = c) }
        refreshHeader()
        ParentAlertService.start(this)
        attachListeners()
    }

    override fun onResume() {
        super.onResume()
        if (::code.isInitialized) refreshHeader()
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(com.tarek.guardian.R.id.tabContent, f)
            .commit()
    }

    private fun refreshHeader() {
        val notifOk = AlertNotifier.canPost(this)
        b.tvHeaderSub.text = buildString {
            append("v${BuildConfig.VERSION_NAME}")
            append("  ·  ")
            append(code)
            if (!notifOk) append("  ·  " + getString(R.string.header_alerts_off))
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun attachListeners() {
        attachedAt = System.currentTimeMillis()

        FirebaseRepo.claimDevice(code) { ok ->
            if (ok) FirebaseRepo.ensureOwner(code)
            if (!ok) {
                GuardianState.update {
                    it.copy(
                        error = getString(R.string.db_unreachable)
                    )
                }
            }

            FirebaseRepo.listenChildInfo(code) { model, lastSeen, _, battery, charging, adminActive, vpnReady, accessibilityActive ->
                GuardianState.update {
                    it.copy(
                        model = model, lastSeen = lastSeen,
                        battery = battery, charging = charging,
                        adminActive = adminActive, vpnReady = vpnReady,
                        accessibilityActive = accessibilityActive, error = null
                    )
                }
            }

            FirebaseRepo.listenInstalledApps(code) { apps ->
                GuardianState.update { it.copy(installedApps = apps) }
            }

            FirebaseRepo.listenPolicy(code) { p ->
                GuardianState.update { it.copy(policy = p) }
            }

            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            FirebaseRepo.listenUsageToday(code, today) { usage ->
                GuardianState.update { it.copy(usage = usage.filterKeys { k -> k != packageName }) }
            }

            FirebaseRepo.listenLocation(code) { lat, lng, ts ->
                GuardianState.update { it.copy(lat = lat, lng = lng, locationTs = ts) }
            }

            FirebaseRepo.listenRequests(code) { list ->
                GuardianState.update { it.copy(requests = list) }
            }

            FirebaseRepo.listenTrack(code, today) { points ->
                GuardianState.update { it.copy(track = points) }
            }

            FirebaseRepo.listenEvents(code) { e ->
                events.removeAll { it.id == e.id }
                events.add(e)
                val recent = events.sortedByDescending { it.ts }.take(120)
                GuardianState.update { it.copy(events = recent) }
            }

            FirebaseRepo.listenAlerts(code) { alert ->
                if (alert.ts >= attachedAt && !alert.seen) {
                    AlertNotifier.show(this, alert)
                    FirebaseRepo.markAlertSeen(code, alert.id)
                }
                alerts.removeAll { it.id == alert.id }
                alerts.add(alert)
                val sorted = alerts.sortedByDescending { it.ts }.take(20)
                GuardianState.update { it.copy(alerts = sorted) }
            }
        }
    }

    // ── Actions the tabs call into ────────────────────────────────────────────

    fun sendCommand(type: String, confirmation: String) {
        if (!::code.isInitialized) return
        FirebaseRepo.pushCommand(code, Command(type = type))
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }

    fun decideRequest(id: String, approve: Boolean) {
        if (!::code.isInitialized) return
        com.tarek.guardian.alerts.RequestNotifier.cancel(this, id)
        FirebaseRepo.decideRequest(code, id, approve) { ok ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(if (!ok) R.string.request_decide_failed else if (approve) R.string.request_approved_toast else R.string.request_denied_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun savePolicy(mutate: (com.tarek.guardian.data.Policy) -> Unit) {
        if (!::code.isInitialized) return
        val p = GuardianState.snapshot.policy
        mutate(p)
        FirebaseRepo.setPolicy(code, p)
    }

    /**
     * Opens internet for exactly [minutes], then it re-blocks itself.
     *
     * Written straight to the policy rather than through [sendCommand]: the
     * child's [com.tarek.guardian.monitor.MonitorService] already reacts
     * to a policy change immediately (it un-tunnels the VPN in `applyPolicy`),
     * so a command round-trip isn't needed — and using one here would race
     * against `internetTimerUntil`, since BLOCK_INTERNET/ALLOW_INTERNET clear
     * that field to keep a manual toggle from fighting a stale timer.
     */
    fun openInternetTimer(minutes: Int) {
        savePolicy {
            it.internetBlocked = false
            it.internetTimerUntil = System.currentTimeMillis() + minutes * 60_000L
        }
        Toast.makeText(
            this,
            getString(R.string.internet_open_for, GuardianState.formatDuration(minutes)),
            Toast.LENGTH_LONG
        ).show()
    }

    fun openPairingWindow() {
        if (!::code.isInitialized) return
        Toast.makeText(this, getString(R.string.pair_opening), Toast.LENGTH_SHORT).show()
        FirebaseRepo.openPairing(code) { ok, err ->
            if (isFinishing) return@openPairing
            val msg = if (ok) getString(R.string.pair_window_open, code)
                      else getString(R.string.pair_window_failed, pairingErrorText(this, err))
            AlertDialog.Builder(this)
                .setTitle(if (ok) "✓" else "✗")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !AlertNotifier.canPost(this)
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.open_notif_settings_hint),
                Toast.LENGTH_LONG).show()
        }
    }

    fun openAbout() = startActivity(Intent(this, AboutActivity::class.java))

    /**
     * Asks Android to stop dozing this app.
     *
     * The alert listener is a foreground service, but on Doze-aggressive OEMs
     * (Huawei among the worst) it still gets frozen, which is why notifications
     * arrived in batches instead of at once. Being on the unrestricted list is the
     * only supported fix; the dialog is Android's, so the parent has to accept it.
     */
    fun requestNoDoze() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                pm?.isIgnoringBatteryOptimizations(packageName) == false
            ) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, getString(R.string.battery_already_ok), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.open_battery_hint), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** True when Android will not freeze our listener. */
    fun isBatteryUnrestricted(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(packageName) == true
    } catch (_: Exception) { true }

    private fun ignoreBatteryOptimisations() = Unit

}
