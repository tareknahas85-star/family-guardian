package com.tarek.guardian.ui.tabs

import com.tarek.guardian.L10n
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.materialswitch.MaterialSwitch
import com.tarek.guardian.LocaleManager
import com.tarek.guardian.R
import com.tarek.guardian.alerts.AlertNotifier
import com.tarek.guardian.calls.NumberUtils
import com.tarek.guardian.data.CallMode
import com.tarek.guardian.data.GeoZone
import com.tarek.guardian.data.RewardTask
import com.tarek.guardian.databinding.FragmentSettingsTabBinding
import com.tarek.guardian.monitor.ScheduleEvaluator
import com.tarek.guardian.ui.GuardianState

/**
 * Everything configurable, in one place.
 *
 * Safe zones, time rules and the number lists used to live on a second screen as
 * well. Two screens writing the same policy fields caused a real bug: that screen
 * rendered once, so opening it before the policy arrived left every switch false,
 * and its Save button then wrote those blanks over the parent's real settings —
 * which is how call filtering ended up inverted. There is now exactly one editor
 * per setting.
 */
class SettingsTabFragment : TabBase() {

    private var _b: FragmentSettingsTabBinding? = null
    private val b get() = _b!!

    private var binding = false
    private var appFilter = ""
    private var lastApps: Map<String, String> = emptyMap()
    private var lastBlocked: Set<String> = emptySet()

    /** True once the parent has typed in a number box, so render stops overwriting it. */
    private var numbersDirty = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsTabBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Call mode ──
        b.rgCallMode.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val mode = when (id) {
                R.id.rbCallUnknown   -> CallMode.BLOCK_UNKNOWN
                R.id.rbCallWhitelist -> CallMode.WHITELIST_ONLY
                else                 -> CallMode.OFF
            }
            host?.savePolicy { it.callMode = mode }
        }
        b.swOutgoing.onChange { on -> host?.savePolicy { it.restrictOutgoing = on } }

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { numbersDirty = true }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        b.etPriorityNumbers.addTextChangedListener(watcher)
        b.etAllowedNumbers.addTextChangedListener(watcher)
        b.etBlockedNumbers.addTextChangedListener(watcher)

        b.btnSaveNumbers.setOnClickListener { saveNumbers() }

        // ── Other switches ──
        b.swSos.onChange { on -> host?.savePolicy { it.sosEnabled = on } }
        b.swWeekly.onChange { on -> host?.savePolicy { it.weeklyReport = on } }
        b.swFeed.onChange { on -> host?.savePolicy { it.activityFeedEnabled = on } }
        b.swLockAtLimit.onChange { on -> host?.savePolicy { it.lockWhenLimitReached = on } }
        b.swInternet.onChange { on ->
            host?.sendCommand(
                if (on) "BLOCK_INTERNET" else "ALLOW_INTERNET",
                L10n.s(if (on) R.string.internet_paused else R.string.internet_resumed)
            )
        }
        b.swCamera.onChange { on -> host?.savePolicy { it.cameraDisabled = on } }
        b.swFilterWeb.onChange { on -> host?.savePolicy { it.filterWeb = on } }

        // ── Rewards ──
        b.btnAddTask.setOnClickListener { addTask() }

        // ── Another parent ──
        b.tvSecondParent.text = L10n.s(R.string.second_parent_hint, GuardianState.snapshot.code)
        b.btnInviteParent.setOnClickListener { host?.openPairingWindow() }

        b.sliderLimit.addOnChangeListener { _, value, _ ->
            b.tvLimitValue.text = limitLabel(value.toInt())
        }
        b.sliderLimit.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: com.google.android.material.slider.Slider) {}
                override fun onStopTrackingTouch(s: com.google.android.material.slider.Slider) {
                    if (!binding) host?.savePolicy { it.dailyLimitMinutes = s.value.toInt() }
                }
            }
        )

        b.etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                appFilter = s?.toString()?.trim()?.lowercase() ?: ""
                renderApps(lastApps, lastBlocked)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // ── Zones and rules ──
        b.btnAddZone.setOnClickListener { addZoneHere() }
        b.btnClearZones.setOnClickListener { host?.savePolicy { it.zones = emptyList() } }
        b.btnAddBedtime.setOnClickListener { addRule(ScheduleEvaluator.bedtime()) }
        b.btnAddStudy.setOnClickListener { addRule(ScheduleEvaluator.studyTime()) }
        b.btnClearRules.setOnClickListener { host?.savePolicy { it.schedules = emptyList() } }

        // ── Language ──
        b.rgLang.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val tag = when (id) {
                R.id.rbLangEn -> LocaleManager.EN
                R.id.rbLangAr -> LocaleManager.AR
                else          -> LocaleManager.SYSTEM
            }
            LocaleManager.apply(tag)
        }

        b.btnNotifSettings.setOnClickListener { host?.openNotificationSettings() }
        b.btnAbout.setOnClickListener { host?.openAbout() }
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        b.tvNotifState.text = if (AlertNotifier.canPost(ctx)) {
            L10n.s(R.string.alerts_ok)
        } else {
            L10n.s(R.string.alerts_blocked)
        }
    }

    override fun render(s: GuardianState.Snapshot) {
        binding = true

        when (CallMode.of(s.policy)) {
            CallMode.BLOCK_UNKNOWN  -> b.rbCallUnknown.isChecked = true
            CallMode.WHITELIST_ONLY -> b.rbCallWhitelist.isChecked = true
            else                    -> b.rbCallOff.isChecked = true
        }
        b.swOutgoing.isChecked    = s.policy.restrictOutgoing
        b.swInternet.isChecked    = s.policy.internetBlocked
        b.swSos.isChecked         = s.policy.sosEnabled
        b.swWeekly.isChecked      = s.policy.weeklyReport
        b.swFeed.isChecked        = s.policy.activityFeedEnabled
        b.swLockAtLimit.isChecked = s.policy.lockWhenLimitReached
        b.swCamera.isChecked      = s.policy.cameraDisabled
        b.swFilterWeb.isChecked   = s.policy.filterWeb

        val limit = s.policy.dailyLimitMinutes.coerceIn(0, 480)
        b.sliderLimit.value = limit.toFloat()
        b.tvLimitValue.text = limitLabel(limit)

        // Do not stomp on half-typed input.
        if (!numbersDirty) {
            b.etPriorityNumbers.setText(s.policy.priorityNumbers.joinToString("\n"))
            b.etAllowedNumbers.setText(s.policy.allowedNumbers.joinToString("\n"))
            b.etBlockedNumbers.setText(s.policy.blockedNumbers.joinToString("\n"))
        }

        when (LocaleManager.current(context)) {
            LocaleManager.EN -> b.rbLangEn.isChecked = true
            LocaleManager.AR -> b.rbLangAr.isChecked = true
            else             -> b.rbLangSystem.isChecked = true
        }

        binding = false

        b.tvCallHint.text = buildString {
            append(L10n.s(R.string.emergency_never_blocked))
            if (s.policy.priorityNumbers.isNotEmpty()) {
                append(L10n.p(R.plurals.priority_count, s.policy.priorityNumbers.size))
            }
        }

        b.tvZonesList.text = if (s.policy.zones.isEmpty()) L10n.s(R.string.no_zones_yet)
        else s.policy.zones.joinToString("\n") { L10n.s(R.string.zone_row, it.name, it.radiusM) }

        b.tvRulesList.text = if (s.policy.schedules.isEmpty()) L10n.s(R.string.no_rules_yet)
        else s.policy.schedules.joinToString("\n") { r ->
            "${ScheduleEvaluator.ruleName(r)}  ·  ${ScheduleEvaluator.formatMinute(r.startMinute)}" +
                "–${ScheduleEvaluator.formatMinute(r.endMinute)}"
        }

        b.tvSecondParent.text = L10n.s(R.string.second_parent_hint, s.code)
        renderTasks(s)

        lastApps = s.installedApps
        lastBlocked = s.policy.blockedApps.toSet()
        renderApps(lastApps, lastBlocked)
    }

    // ── Rewards ───────────────────────────────────────────────────────────────

    private fun addTask() {
        val title = b.etTaskTitle.text.toString().trim()
        if (title.isBlank()) {
            Toast.makeText(context, L10n.s(R.string.task_need_title), Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = b.etTaskMinutes.text.toString().trim().toIntOrNull()?.coerceIn(5, 240) ?: 15
        host?.savePolicy { p ->
            p.tasks = p.tasks + RewardTask(id = "t${System.currentTimeMillis()}", title = title, minutes = minutes)
        }
        b.etTaskTitle.setText("")
    }

    private fun renderTasks(s: GuardianState.Snapshot) {
        val hostView = b.tasksHost
        hostView.removeAllViews()
        val inflater = LayoutInflater.from(context)
        if (s.policy.tasks.isEmpty()) {
            hostView.addView(TextView(context).apply {
                text = L10n.s(R.string.no_tasks_parent)
                textSize = 13f
            })
            return
        }
        for (t in s.policy.tasks) {
            val row = inflater.inflate(R.layout.item_action_row, hostView, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = t.title
            row.findViewById<TextView>(R.id.tvRowDetail).text = L10n.s(R.string.task_reward, fmt(t.minutes))
            row.findViewById<TextView>(R.id.btnRowB).apply {
                visibility = View.VISIBLE
                text = L10n.s(R.string.delete)
                setOnClickListener { host?.savePolicy { p -> p.tasks = p.tasks.filter { it.id != t.id } } }
            }
            hostView.addView(row)
        }
    }

    // ── Numbers ───────────────────────────────────────────────────────────────

    private fun saveNumbers() {
        val priority = parse(b.etPriorityNumbers.text.toString())
        val allowed  = parse(b.etAllowedNumbers.text.toString())
        val blocked  = parse(b.etBlockedNumbers.text.toString())

        host?.savePolicy {
            it.priorityNumbers = priority
            it.allowedNumbers = allowed
            it.blockedNumbers = blocked
        }
        numbersDirty = false

        // A number that is too short to be a real line is almost always a typo,
        // and in whitelist mode a typo means that person gets blocked.
        val suspicious = (priority + allowed).filter { NumberUtils.digitsOnly(it).length in 1..8 }
        val msg = if (suspicious.isEmpty()) {
            L10n.s(R.string.saved)
        } else {
            L10n.s(R.string.saved_check_short, suspicious.joinToString(", "))
        }
        Toast.makeText(context, msg, if (suspicious.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }

    private fun parse(raw: String): List<String> =
        raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    // ── Zones and rules ───────────────────────────────────────────────────────

    private fun addRule(rule: com.tarek.guardian.data.ScheduleRule) {
        host?.savePolicy { p ->
            p.schedules = p.schedules.filter { it.id != rule.id } + rule
        }
    }

    private fun addZoneHere() {
        val ctx = context ?: return
        val act = activity ?: return
        if (ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                act, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 301
            )
            return
        }
        val existing = GuardianState.snapshot.policy.zones.size
        val name = b.etZoneName.text.toString().trim().ifBlank { L10n.s(R.string.zone_default_name, existing + 1) }
        val radius = b.etZoneRadius.text.toString().trim().toIntOrNull()?.coerceIn(50, 5000) ?: 200

        Toast.makeText(ctx, L10n.s(R.string.locating), Toast.LENGTH_SHORT).show()
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc == null) {
                        Toast.makeText(ctx, L10n.s(R.string.no_position), Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }
                    host?.savePolicy { p ->
                        p.zones = p.zones + GeoZone(
                            id = "z${System.currentTimeMillis()}",
                            name = name, lat = loc.latitude, lng = loc.longitude,
                            radiusM = radius, notifyOnExit = true, notifyOnEnter = true
                        )
                    }
                    b.etZoneName.setText("")
                }
        } catch (_: SecurityException) {
        }
    }

    // ── Apps ──────────────────────────────────────────────────────────────────

    private fun limitLabel(minutes: Int) = if (minutes <= 0) L10n.s(R.string.off) else fmt(minutes)

    private fun renderApps(apps: Map<String, String>, blocked: Set<String>) {
        val hostView = b.appsHost
        hostView.removeAllViews()

        if (apps.isEmpty()) {
            b.tvAppsHint.text = L10n.s(R.string.apps_not_sent)
            return
        }

        val matching = apps.entries
            .filter { appFilter.isEmpty() || it.value.lowercase().contains(appFilter) }
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> { it.key in blocked }
                    .thenBy { it.value.lowercase() }
            )

        b.tvAppsHint.text = L10n.s(R.string.apps_restricted_of, blocked.size, apps.size) +
            if (appFilter.isNotEmpty()) "  ·  " + L10n.s(R.string.apps_matching, matching.size) else ""

        val inflater = LayoutInflater.from(context)
        for ((pkg, label) in matching.take(40)) {
            val row = inflater.inflate(R.layout.item_app_toggle, hostView, false)
            row.findViewById<TextView>(R.id.tvAppName).text = label
            val isBlocked = pkg in blocked
            val limitMinutes = appLimitMinutes(pkg)
            row.findViewById<TextView>(R.id.tvAppState).text = when {
                isBlocked         -> L10n.s(R.string.app_restricted)
                limitMinutes > 0  -> L10n.s(R.string.app_limit_day, fmt(limitMinutes))
                else              -> L10n.s(R.string.app_allowed)
            }
            row.findViewById<TextView>(R.id.btnAppLimit).apply {
                text = if (limitMinutes > 0) L10n.s(R.string.app_limit_btn, fmt(limitMinutes)) else L10n.s(R.string.set_limit)
                setOnClickListener { showAppLimitDialog(pkg, label) }
            }
            val sw = row.findViewById<MaterialSwitch>(R.id.swApp)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = isBlocked
            sw.setOnCheckedChangeListener { _, checked ->
                host?.savePolicy { p ->
                    val set = p.blockedApps.toMutableSet()
                    if (checked) set.add(pkg) else set.remove(pkg)
                    p.blockedApps = set.toList()
                }
            }
            hostView.addView(row)
        }
        if (matching.size > 40) {
            hostView.addView(TextView(context).apply {
                text = L10n.s(R.string.apps_more, matching.size - 40)
                textSize = 12f
                setPadding(0, 12, 0, 0)
            })
        }
    }

    // ── Per-app time limits ──────────────────────────────────────────────────
    // Stored in Policy.limits as "pkg=minutes" strings — MonitorService.enforce()
    // already reads this list and bounces the app once today's usage passes it.

    private fun appLimitMinutes(pkg: String): Int =
        GuardianState.snapshot.policy.limits
            .firstOrNull { it.substringBefore("=", "") == pkg }
            ?.substringAfter("=", "")
            ?.toIntOrNull() ?: 0

    private fun setAppLimit(pkg: String, minutes: Int) {
        host?.savePolicy { p ->
            val current = p.limits.mapNotNull { entry ->
                val parts = entry.split("=")
                if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
            }.toMap().toMutableMap()
            if (minutes <= 0) current.remove(pkg) else current[pkg] = minutes
            p.limits = current.map { (k, v) -> "$k=$v" }
        }
    }

    private fun showAppLimitDialog(pkg: String, label: String) {
        val ctx = context ?: return
        val presets = listOf(0, 10, 15, 30, 60, 120, 240)
        val labels = presets.map { if (it == 0) L10n.s(R.string.no_limit) else fmt(it) } + L10n.s(R.string.custom)
        AlertDialog.Builder(ctx)
            .setTitle(L10n.s(R.string.daily_limit_for, label))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    setAppLimit(pkg, presets[which])
                } else {
                    showCustomMinutesDialog(pkg, label)
                }
            }
            .show()
    }

    private fun showCustomMinutesDialog(pkg: String, label: String) {
        val ctx = context ?: return
        val current = appLimitMinutes(pkg)
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = L10n.s(R.string.minutes_per_day)
            if (current > 0) setText(current.toString())
        }
        AlertDialog.Builder(ctx)
            .setTitle(L10n.s(R.string.daily_limit_for, label))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                setAppLimit(pkg, input.text.toString().toIntOrNull() ?: 0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun CompoundButton.onChange(action: (Boolean) -> Unit) {
        setOnCheckedChangeListener { _, checked -> if (!binding) action(checked) }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
