package com.tarek.guardian.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.ValueEventListener
import com.tarek.guardian.L10n
import com.tarek.guardian.Prefs
import com.tarek.guardian.R
import com.tarek.guardian.data.ChildRequest
import com.tarek.guardian.data.FirebaseRepo
import com.tarek.guardian.data.Policy
import com.tarek.guardian.databinding.ActivityChildSetupBinding
import com.tarek.guardian.monitor.UsageTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The child's side of "extra time" and "rewards": how much time is left today,
 * a button to ask for more, and the parent's tasks with a "done" button each.
 * Every ask becomes a [ChildRequest] the parent approves or denies.
 */
class ChildRequestsPanel(
    private val act: AppCompatActivity,
    private val b: ActivityChildSetupBinding
) {
    private var code = ""
    private var policy = Policy()
    private var requests: List<ChildRequest> = emptyList()
    private var policyL: ValueEventListener? = null
    private var requestsL: ValueEventListener? = null

    fun start() {
        b.btnAskTime.setOnClickListener { askForTime() }
        attach()
    }

    /** (Re)attaches to the current pair code; safe to call repeatedly. */
    fun attach() {
        val c = Prefs.getPairCode(act) ?: ""
        if (c.isBlank()) { b.cardRequests.visibility = View.GONE; return }
        b.cardRequests.visibility = View.VISIBLE
        if (c == code && policyL != null) { render(); return }
        stop()
        code = c
        policyL = FirebaseRepo.listenPolicy(code) { p -> policy = p; act.runOnUiThread { render() } }
        requestsL = FirebaseRepo.listenRequests(code) { r -> requests = r; act.runOnUiThread { render() } }
    }

    fun stop() {
        if (code.isBlank()) return
        policyL?.let { FirebaseRepo.detachPolicy(code, it) }
        requestsL?.let { FirebaseRepo.detachRequests(code, it) }
        policyL = null; requestsL = null
    }

    fun askForTime() {
        if (code.isBlank()) {
            Toast.makeText(act, L10n.s(R.string.pair_first), Toast.LENGTH_LONG).show()
            return
        }
        if (requests.any { it.type == ChildRequest.TIME && it.status == ChildRequest.PENDING }) {
            Toast.makeText(act, L10n.s(R.string.request_already_waiting), Toast.LENGTH_LONG).show()
            return
        }
        val options = listOf(15, 30, 60)
        AlertDialog.Builder(act)
            .setTitle(R.string.ask_more_time)
            .setItems(options.map { L10n.duration(it) }.toTypedArray()) { _, which ->
                send(ChildRequest(type = ChildRequest.TIME, minutes = options[which]))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun send(req: ChildRequest) {
        FirebaseRepo.pushRequest(code, req) { ok ->
            act.runOnUiThread {
                Toast.makeText(
                    act, L10n.s(if (ok) R.string.request_sent else R.string.request_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun render() {
        // Time left today, from this phone's own usage counter.
        val limit = policy.dailyLimitMinutes
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val bonus = if (policy.bonusDate == today) policy.bonusMinutes else 0
        b.tvTimeLeft.text = if (limit <= 0) {
            L10n.s(R.string.child_no_limit)
        } else {
            val used = UsageTracker.todayUsageMinutes(act).filterKeys { it != act.packageName }.values.sum()
            val left = (limit + bonus - used).coerceAtLeast(0)
            L10n.s(R.string.child_time_left, L10n.duration(left)) +
                if (bonus > 0) "\n" + L10n.s(R.string.child_bonus_today, L10n.duration(bonus)) else ""
        }

        // The newest request and what happened to it.
        val last = requests.maxByOrNull { it.ts }
        b.tvRequestStatus.text = when {
            last == null -> ""
            last.status == ChildRequest.PENDING -> L10n.s(R.string.request_waiting, requestLabel(last))
            last.status == ChildRequest.APPROVED -> L10n.s(R.string.request_approved, requestLabel(last))
            else -> L10n.s(R.string.request_denied, requestLabel(last))
        }

        // Tasks.
        val host = b.tasksHost
        host.removeAllViews()
        b.tvNoTasks.visibility = if (policy.tasks.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(act)
        for (t in policy.tasks) {
            val row = inflater.inflate(R.layout.item_action_row, host, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = t.title
            row.findViewById<TextView>(R.id.tvRowDetail).text =
                L10n.s(R.string.task_reward, L10n.duration(t.minutes))
            val waiting = requests.any {
                it.type == ChildRequest.TASK && it.taskId == t.id && it.status == ChildRequest.PENDING
            }
            row.findViewById<TextView>(R.id.btnRowA).apply {
                visibility = View.VISIBLE
                text = L10n.s(if (waiting) R.string.task_waiting else R.string.task_done)
                isEnabled = !waiting
                setOnClickListener {
                    send(ChildRequest(type = ChildRequest.TASK, minutes = t.minutes, taskId = t.id, title = t.title))
                }
            }
            host.addView(row)
        }
    }

    private fun requestLabel(r: ChildRequest): String =
        if (r.type == ChildRequest.TASK) L10n.s(R.string.request_task_label, r.title, L10n.duration(r.minutes))
        else L10n.s(R.string.request_time_label, L10n.duration(r.minutes))

    companion object {
        const val EXTRA_ASK_TIME = "ask_time"
    }
}
