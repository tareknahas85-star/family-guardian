package com.tarek.guardian.ui.tabs

import com.tarek.guardian.ui.Timeline
import com.tarek.guardian.L10n
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tarek.guardian.R
import com.tarek.guardian.databinding.FragmentActivityTabBinding
import com.tarek.guardian.monitor.ScheduleEvaluator
import com.tarek.guardian.ui.GuardianState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tab 3 — how the time was actually spent, plus what happened. */
class ActivityTabFragment : TabBase() {

    private var _b: FragmentActivityTabBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentActivityTabBinding.inflate(i, c, false)
        return b.root
    }

    override fun render(s: GuardianState.Snapshot) {
        val total = s.usage.values.sum()
        b.tvTotal.text = if (s.usage.isEmpty()) "—" else fmt(total)

        b.tvBreakdown.text = if (s.usage.isEmpty()) {
            L10n.s(R.string.no_usage_grant)
        } else {
            L10n.p(R.plurals.apps_used_today, s.usage.size)
        }

        renderSchedules(s)
        renderTimeline(s)
        renderBars(s)

        b.tvTimeline.text = if (s.alerts.isEmpty()) L10n.s(R.string.nothing_yet) else {
            val f = SimpleDateFormat("d MMM، HH:mm".takeIf { L10n.isArabic } ?: "MMM d, HH:mm", L10n.dateLocale())
            s.alerts.take(12).joinToString("\n\n") { a ->
                "${f.format(Date(a.ts))}\n${a.title}\n${a.body}"
            }
        }
    }

    /** Today's places, as stops with times; tap one to see it on the map. */
    private fun renderTimeline(s: GuardianState.Snapshot) {
        val stops = Timeline.stops(s.track, s.policy.zones)
        b.tvTimelineEmpty.visibility = if (stops.isEmpty()) View.VISIBLE else View.GONE
        b.btnRoute.visibility = if (stops.size >= 2) View.VISIBLE else View.GONE
        b.btnRoute.setOnClickListener { openUrl(Timeline.routeUrl(stops)) }

        val hostView = b.stopsHost
        hostView.removeAllViews()
        val time = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        val inflater = LayoutInflater.from(context)
        for ((i, st) in stops.withIndex().reversed()) {
            val row = inflater.inflate(R.layout.item_action_row, hostView, false)
            row.findViewById<TextView>(R.id.tvRowIcon).apply {
                visibility = View.VISIBLE
                text = if (i == stops.lastIndex) "📍" else "•"
            }
            row.findViewById<TextView>(R.id.tvRowTitle).text =
                st.zone ?: String.format(Locale.ENGLISH, "%.4f, %.4f", st.lat, st.lng)
            row.findViewById<TextView>(R.id.tvRowDetail).text =
                if (st.to - st.from < 5 * 60_000L) time.format(Date(st.from))
                else L10n.s(R.string.timeline_range, time.format(Date(st.from)), time.format(Date(st.to)))
            row.findViewById<TextView>(R.id.btnRowA).apply {
                visibility = View.VISIBLE
                text = L10n.s(R.string.action_map)
                setOnClickListener { openUrl("https://maps.google.com/?q=${st.lat},${st.lng}") }
            }
            hostView.addView(row)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, L10n.s(R.string.no_app_for_link), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Bedtime and study windows, with whichever one is running right now marked. */
    private fun renderSchedules(s: GuardianState.Snapshot) {
        if (s.policy.schedules.isEmpty()) {
            b.tvSchedules.text = L10n.s(R.string.no_rules_hint)
            return
        }
        val active = ScheduleEvaluator.evaluate(s.policy.schedules)
        b.tvSchedules.text = s.policy.schedules.joinToString("\n") { r ->
            val running = r.name in active.activeRuleNames
            val window = "${ScheduleEvaluator.formatMinute(r.startMinute)}" +
                "–${ScheduleEvaluator.formatMinute(r.endMinute)}"
            val effects = listOfNotNull(
                if (r.lockDevice) L10n.s(R.string.rule_locks_phone) else null,
                if (r.blockInternet) L10n.s(R.string.rule_no_internet) else null
            ).joinToString(if (L10n.isArabic) "، " else ", ").ifBlank { L10n.s(R.string.rule_apps_only) }
            "${if (running) L10n.s(R.string.rule_active) else "○ "}${ScheduleEvaluator.ruleName(r)}  ·  $window  ·  $effects"
        }
    }

    /**
     * A bar per app, widths relative to the heaviest one. Done with plain views
     * rather than a chart library — this is five rows of data, not a dashboard.
     */
    private fun renderBars(s: GuardianState.Snapshot) {
        val host = b.barsHost
        host.removeAllViews()
        if (s.usage.isEmpty()) return

        val top = s.usage.entries.sortedByDescending { it.value }.take(8)
        val max = top.first().value.coerceAtLeast(1)
        val inflater = LayoutInflater.from(context)

        for ((pkg, min) in top) {
            val row = inflater.inflate(R.layout.item_usage_bar, host, false)
            row.findViewById<TextView>(R.id.tvName).text = appLabel(pkg)
            row.findViewById<TextView>(R.id.tvValue).text = fmt(min)

            val bar = row.findViewById<View>(R.id.bar)
            val track = bar.parent as? View

            // Width is a fraction of the track, applied once the track is measured.
            //
            // Do not cast to a specific LayoutParams subclass here. The bar sits
            // inside a FrameLayout, so its params are FrameLayout.LayoutParams and
            // casting them to LinearLayout.LayoutParams threw ClassCastException,
            // which crashed the whole app whenever this tab opened. Width and
            // height live on ViewGroup.LayoutParams, so no cast is needed at all.
            track?.post {
                val w = track.width
                if (w > 0) {
                    val lp = bar.layoutParams ?: return@post
                    lp.width = (w * min.toFloat() / max).toInt().coerceAtLeast(6)
                    bar.layoutParams = lp
                }
            }
            host.addView(row)
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
