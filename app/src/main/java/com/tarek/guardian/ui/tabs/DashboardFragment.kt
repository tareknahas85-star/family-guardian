package com.tarek.guardian.ui.tabs

import com.tarek.guardian.L10n
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.tarek.guardian.R
import com.tarek.guardian.alerts.AlertNotifier
import com.tarek.guardian.data.ActivityEvent
import com.tarek.guardian.data.Alert
import com.tarek.guardian.data.ChildRequest
import com.tarek.guardian.databinding.FragmentDashboardBinding
import com.tarek.guardian.ui.GuardianState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The one screen a parent actually looks at: where the child is, how long the
 * phone has been used, what happened today, and the controls.
 *
 * The old Kids tab was folded in here. It showed the device, its position and its
 * protection status — all of which belong next to the location card rather than
 * behind another tap.
 */
class DashboardFragment : TabBase() {

    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDashboardBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnLock.setOnClickListener   { host?.sendCommand("LOCK_NOW", L10n.s(R.string.lock_sent)) }
        b.btnUnlock.setOnClickListener { host?.sendCommand("UNLOCK", L10n.s(R.string.unlock_sent)) }
        b.btnNetOff.setOnClickListener { host?.sendCommand("BLOCK_INTERNET", L10n.s(R.string.internet_paused)) }
        b.btnNetOn.setOnClickListener  { host?.sendCommand("ALLOW_INTERNET", L10n.s(R.string.internet_resumed)) }
        b.btnFixNotif.setOnClickListener { host?.openNotificationSettings() }
        b.btnFixDoze.setOnClickListener { host?.requestNoDoze() }
        b.btnLocate.setOnClickListener { host?.sendCommand("LOCATE", L10n.s(R.string.locating_child)) }
        b.btnPair.setOnClickListener { host?.openPairingWindow() }
        b.btnMap.setOnClickListener { openMap() }

        b.sliderNetTimer.addOnChangeListener { _, value, _ ->
            b.tvNetTimerValue.text = fmt(value.toInt())
        }
        b.tvNetTimerValue.text = fmt(b.sliderNetTimer.value.toInt())
        b.btnOpenNetTimer.setOnClickListener {
            host?.openInternetTimer(b.sliderNetTimer.value.toInt())
        }

        b.btnTestNotif.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val ok = AlertNotifier.show(
                ctx,
                Alert(
                    id = "test-${System.currentTimeMillis()}",
                    type = Alert.SOS,
                    title = L10n.s(R.string.test_alert_title),
                    body = L10n.s(R.string.test_alert_body),
                    ts = System.currentTimeMillis()
                )
            )
            Toast.makeText(
                ctx,
                if (ok) L10n.s(R.string.test_sent)
                else L10n.s(R.string.test_blocked),
                Toast.LENGTH_LONG
            ).show()
            refreshNotifWarning()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotifWarning()
    }

    /**
     * Two separate reasons alerts can fail to arrive, so check both: the
     * permission, and whether Android is allowed to freeze our listener. The
     * banner shows if either is a problem.
     */
    private fun refreshNotifWarning() {
        val ctx = context ?: return
        val canPost = AlertNotifier.canPost(ctx)
        val noDoze = host?.isBatteryUnrestricted() ?: true

        b.boxNotifWarning.visibility = if (canPost && noDoze) View.GONE else View.VISIBLE
        b.btnFixNotif.visibility = if (canPost) View.GONE else View.VISIBLE
        b.btnFixDoze.visibility = if (noDoze) View.GONE else View.VISIBLE
    }

    override fun render(s: GuardianState.Snapshot) {
        renderRequests(s)
        renderLocation(s)
        renderScreenTime(s)
        renderDevice(s)
        renderNetTimer(s)
        renderTodayFeed(s)
    }

    /**
     * Shows the countdown while a temporary-open window is running, so the
     * parent isn't left guessing whether it's still active.
     */
    private fun renderNetTimer(s: GuardianState.Snapshot) {
        val until = s.policy.internetTimerUntil
        val now = System.currentTimeMillis()
        b.tvNetTimerStatus.text = if (until > now) {
            val remaining = ((until - now) / 60_000L).toInt().coerceAtLeast(1)
            L10n.s(R.string.net_timer_running, fmt(remaining))
        } else {
            L10n.s(R.string.net_timer_idle)
        }
    }

    /** Pending asks from the child, each with Approve / Deny. */
    private fun renderRequests(s: GuardianState.Snapshot) {
        val pending = s.requests.filter { it.status == ChildRequest.PENDING }.sortedByDescending { it.ts }
        b.cardRequests.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        val hostView = b.requestsHost
        hostView.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (r in pending) {
            val row = inflater.inflate(R.layout.item_action_row, hostView, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text =
                if (r.type == ChildRequest.TASK) L10n.s(R.string.req_task_body, r.title, fmt(r.minutes))
                else L10n.s(R.string.req_time_body, fmt(r.minutes))
            row.findViewById<TextView>(R.id.tvRowDetail).text = GuardianState.relativeTime(r.ts)
            row.findViewById<TextView>(R.id.btnRowA).apply {
                visibility = View.VISIBLE
                text = L10n.s(R.string.approve)
                setOnClickListener { host?.decideRequest(r.id, true) }
            }
            row.findViewById<TextView>(R.id.btnRowB).apply {
                visibility = View.VISIBLE
                text = L10n.s(R.string.deny)
                setOnClickListener { host?.decideRequest(r.id, false) }
            }
            hostView.addView(row)
        }
    }

    private fun renderLocation(s: GuardianState.Snapshot) {
        b.tvLocation.text = when {
            s.error != null  -> L10n.s(R.string.cannot_connect)
            s.hasLocation    -> String.format(Locale.ENGLISH, "%.4f, %.4f", s.lat, s.lng)
            s.lastSeen == 0L -> L10n.s(R.string.not_paired_yet)
            else             -> L10n.s(R.string.no_location_yet)
        }
        b.tvZoneState.text = s.error
            ?: if (s.hasLocation) L10n.s(R.string.updated_ago, GuardianState.relativeTime(s.locationTs)) else ""
        b.tvConnection.text = if (s.isOnline) L10n.s(R.string.online) else L10n.s(R.string.offline)
        b.tvBattery.text = when {
            s.battery < 0 -> "—"
            s.charging    -> "${s.battery}% ⚡"
            else          -> "${s.battery}%"
        }
    }

    private fun renderScreenTime(s: GuardianState.Snapshot) {
        val total = s.usage.values.sum()
        val limit = dailyLimitMinutes(s)
        b.tvUsedTotal.text = fmt(total)
        val bonus = bonusToday(s)
        b.tvLimit.text = if (limit > 0) {
            L10n.s(R.string.limit_x, fmt(limit)) +
                if (bonus > 0) "  ·  " + L10n.s(R.string.bonus_today, fmt(bonus)) else ""
        } else L10n.s(R.string.no_daily_limit)
        if (limit > 0) {
            b.ring.progress = total.toFloat() / (limit + bonus)
            val left = limit + bonus - total
            b.tvRemaining.text = if (left >= 0) L10n.s(R.string.time_left, fmt(left)) else L10n.s(R.string.time_over, fmt(-left))
        } else {
            b.ring.progress = 0f
            b.tvRemaining.text = ""
        }

        b.tvTopApps.text = if (s.usage.isEmpty()) {
            L10n.s(R.string.no_usage_long)
        } else {
            s.usage.entries.sortedByDescending { it.value }.take(4)
                .joinToString("\n") { (pkg, min) -> "${appLabel(pkg)}  ·  ${fmt(min)}" }
        }
    }

    private fun renderDevice(s: GuardianState.Snapshot) {
        b.tvDevice.text = s.model.ifBlank {
            if (s.lastSeen == 0L) L10n.s(R.string.not_connected) else L10n.s(R.string.child_device)
        }
        b.tvSeen.text = when {
            s.error != null           -> s.error
            s.lastSeen == 0L          -> L10n.s(R.string.enter_code_hint)
            s.isOnline                -> L10n.s(R.string.online_now)
            s.minutesSinceSeen < 60   -> L10n.p(R.plurals.last_seen_min, s.minutesSinceSeen.toInt())
            s.minutesSinceSeen < 1440 -> L10n.p(R.plurals.last_seen_hour, (s.minutesSinceSeen / 60).toInt())
            else                      -> L10n.p(R.plurals.offline_days, (s.minutesSinceSeen / 1440).toInt())
        }

        // Reported by the child device itself (see MonitorService.cycle()) —
        // this used to check PolicyManager on the *parent's own* phone, which
        // is a different device and told the parent nothing true about the
        // child's actual protection state.
        val ctx2 = context
        b.tvAdminStatus.text = L10n.s(if (s.adminActive) R.string.dash_admin_ok else R.string.dash_admin_bad)
        b.tvVpnStatus.text = L10n.s(if (s.externalVpn) R.string.dash_vpn_external else if (s.vpnReady) R.string.dash_vpn_ok else R.string.dash_vpn_bad)
        // App blocking, whole-device lock, time limits and schedules all run
        // through the Accessibility Service — a separate OS grant from Device
        // Admin/VPN above. Some phones (Huawei especially) silently turn this
        // off on their own; this line is the only way to see that happened.
        b.tvAccessibilityStatus.text = L10n.s(if (s.accessibilityActive) R.string.dash_acc_ok else R.string.dash_acc_bad)
        if (ctx2 != null) {
            b.tvAdminStatus.setTextColor(ctx2.getColor(if (s.adminActive) R.color.secondary_dark else R.color.error))
            b.tvVpnStatus.setTextColor(ctx2.getColor(if (s.vpnReady || s.externalVpn) R.color.secondary_dark else R.color.error))
            b.tvAccessibilityStatus.setTextColor(ctx2.getColor(if (s.accessibilityActive) R.color.secondary_dark else R.color.error))
        }

        b.tvZonesSummary.text = if (s.policy.zones.isEmpty()) L10n.s(R.string.no_zones_set)
        else L10n.p(R.plurals.zones_summary, s.policy.zones.size, s.policy.zones.size, s.policy.zones.joinToString(", ") { it.name })
    }

    /**
     * Today's events only, newest first.
     *
     * The parent asked for a list that clears each day: rather than deleting
     * anything, this filters to events since local midnight, so at 00:00 the card
     * is empty again and the underlying history is still there for the Live tab.
     * Deleting would have thrown away the record.
     */
    private fun renderTodayFeed(s: GuardianState.Snapshot) {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Alerts matter as much as events, so show both in one time-ordered list.
        val rows = ArrayList<Triple<Long, String, String>>()
        s.events.filter { it.ts >= midnight }.forEach {
            rows.add(Triple(it.ts, it.title.ifBlank { ActivityEvent.label(it.type) },
                ActivityEvent.label(it.type)))
        }
        s.alerts.filter { it.ts >= midnight }.forEach {
            rows.add(Triple(it.ts, it.title, it.body))
        }
        rows.sortByDescending { it.first }

        b.tvFeedCount.text = if (rows.isEmpty()) "" else "${rows.size}"
        b.tvFeedEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val hostView = b.feedHost
        hostView.removeAllViews()
        if (rows.isEmpty()) return

        val time = SimpleDateFormat("HH:mm", Locale.US)
        val inflater = LayoutInflater.from(context)
        for ((ts, title, meta) in rows.take(30)) {
            val row = inflater.inflate(R.layout.item_event, hostView, false)
            row.findViewById<TextView>(R.id.tvIcon).text = "•"
            row.findViewById<TextView>(R.id.tvEventTitle).text = title
            row.findViewById<TextView>(R.id.tvEventMeta).text =
                "${time.format(Date(ts))}  ·  $meta"
            hostView.addView(row)
        }
    }

    private fun openMap() {
        val s = GuardianState.snapshot
        if (!s.hasLocation) {
            Toast.makeText(context, L10n.s(R.string.no_position_yet), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}(${Uri.encode(L10n.s(R.string.map_label_child))})"))
            )
        } catch (_: Exception) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${s.lat},${s.lng}"))
            )
        }
    }

    private fun bonusToday(s: GuardianState.Snapshot): Int {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return if (s.policy.bonusDate == today) s.policy.bonusMinutes else 0
    }

    private fun dailyLimitMinutes(s: GuardianState.Snapshot): Int =
        if (s.policy.dailyLimitMinutes > 0) s.policy.dailyLimitMinutes
        else s.policy.limits.mapNotNull { it.split("=").getOrNull(1)?.toIntOrNull() }.maxOrNull() ?: 0

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
