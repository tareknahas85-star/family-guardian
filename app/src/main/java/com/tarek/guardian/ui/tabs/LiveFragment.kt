package com.tarek.guardian.ui.tabs

import com.tarek.guardian.L10n
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tarek.guardian.R
import com.tarek.guardian.data.ActivityEvent
import com.tarek.guardian.databinding.FragmentLiveBinding
import com.tarek.guardian.ui.GuardianState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live feed: everything that happens on the child device, newest first, plus
 * control over which of those events also raise a notification.
 *
 * ## Notification defaults
 * The parent asked for an instant notification on every action, so every event
 * type notifies by default (see [ActivityEvent.ALL] and
 * [com.tarek.guardian.alerts.AlertNotifier.showEvent], which replaces the
 * same type's last notification rather than stacking, so a burst of app
 * switches updates one line instead of flooding the shade). This dialog is
 * still here for a parent who wants to trim it back down later.
 */
class LiveFragment : TabBase() {

    private var _b: FragmentLiveBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentLiveBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.tvToggleChooser.setOnClickListener { chooseEventTypes() }
    }

    override fun render(s: GuardianState.Snapshot) {
        val chosen = s.policy.notifyOnEvents
        b.tvNotifySummary.text = if (chosen.isEmpty()) {
            L10n.s(R.string.notify_nothing)
        } else {
            chosen.joinToString(if (L10n.isArabic) "، " else ", ") { ActivityEvent.label(it) }
        }

        b.tvLiveHint.text = when {
            !s.policy.activityFeedEnabled ->
                L10n.s(R.string.feed_off)
            s.events.isEmpty() && s.lastSeen == 0L ->
                L10n.s(R.string.feed_pair_first)
            s.events.isEmpty() ->
                L10n.s(R.string.feed_need_acc)
            else -> L10n.p(R.plurals.recent_events, s.events.size)
        }

        renderFeed(s)
    }

    private fun renderFeed(s: GuardianState.Snapshot) {
        val hostView = b.feedHost
        hostView.removeAllViews()
        if (s.events.isEmpty()) return

        val time = SimpleDateFormat("HH:mm", Locale.US)
        val day = SimpleDateFormat("d MMM", L10n.dateLocale())
        val today = day.format(Date())
        val inflater = LayoutInflater.from(context)

        for (e in s.events.sortedByDescending { it.ts }.take(60)) {
            val row = inflater.inflate(R.layout.item_event, hostView, false)
            row.findViewById<TextView>(R.id.tvIcon).text = icon(e.type)
            row.findViewById<TextView>(R.id.tvEventTitle).text =
                e.title.ifBlank { ActivityEvent.label(e.type) }

            val stamp = day.format(Date(e.ts)).let { d ->
                if (d == today) time.format(Date(e.ts)) else "$d ${time.format(Date(e.ts))}"
            }
            val meta = listOfNotNull(
                stamp,
                ActivityEvent.label(e.type),
                e.detail.takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")
            row.findViewById<TextView>(R.id.tvEventMeta).text = meta

            hostView.addView(row)
        }
    }

    private fun icon(type: String) = when (type) {
        ActivityEvent.APP_OPENED      -> "▶"
        ActivityEvent.APP_BLOCKED     -> "⛔"
        ActivityEvent.APP_INSTALLED   -> "＋"
        ActivityEvent.APP_UNINSTALLED -> "－"
        ActivityEvent.UNLOCK          -> "🔓"
        ActivityEvent.SCREEN_ON       -> "☀"
        ActivityEvent.SCREEN_OFF      -> "☾"
        ActivityEvent.CALL            -> "📞"
        ActivityEvent.POWER           -> "⚡"
        ActivityEvent.INTERNET        -> "🌐"
        ActivityEvent.LIMIT_REACHED   -> "⏳"
        ActivityEvent.BOOT            -> "⟳"
        else                          -> "•"
    }

    /** Multi-select of the event types that should notify. */
    private fun chooseEventTypes() {
        val ctx = context ?: return
        val types = ActivityEvent.ALL
        val labels = types.map { ActivityEvent.label(it) }.toTypedArray()
        val current = GuardianState.snapshot.policy.notifyOnEvents.toMutableSet()
        val checked = BooleanArray(types.size) { types[it] in current }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.notify_me_about)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) current.add(types[which]) else current.remove(types[which])
            }
            .setPositiveButton(R.string.save) { _, _ ->
                host?.savePolicy { it.notifyOnEvents = current.toList() }
            }
            .setNeutralButton(R.string.everything) { _, _ ->
                host?.savePolicy { it.notifyOnEvents = ActivityEvent.ALL }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
