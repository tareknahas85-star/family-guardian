package com.tarek.guardian.alerts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tarek.guardian.App
import com.tarek.guardian.L10n
import com.tarek.guardian.Prefs
import com.tarek.guardian.R
import com.tarek.guardian.data.ChildRequest
import com.tarek.guardian.data.FirebaseRepo
import com.tarek.guardian.ui.RoleSelectActivity
import kotlin.math.absoluteValue

/**
 * Parent-side notification for a child's request, with Approve and Deny right
 * on it — the whole point is that answering takes one tap, without opening the
 * app.
 */
object RequestNotifier {

    private fun notifId(requestId: String) = ("req:$requestId").hashCode().absoluteValue

    fun show(ctx: Context, r: ChildRequest) {
        if (!AlertNotifier.canPost(ctx)) return
        val title = if (r.type == ChildRequest.TASK) L10n.s(R.string.req_task_title)
                    else L10n.s(R.string.req_time_title)
        val body = if (r.type == ChildRequest.TASK) L10n.s(R.string.req_task_body, r.title, L10n.duration(r.minutes))
                   else L10n.s(R.string.req_time_body, L10n.duration(r.minutes))

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, RoleSelectActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, App.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(r.ts)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(0, L10n.s(R.string.approve), action(ctx, r.id, true))
            .addAction(0, L10n.s(R.string.deny), action(ctx, r.id, false))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notifId(r.id), n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context, requestId: String) =
        NotificationManagerCompat.from(ctx).cancel(notifId(requestId))

    private fun action(ctx: Context, id: String, approve: Boolean): PendingIntent {
        val i = Intent(ctx, RequestActionReceiver::class.java)
            .putExtra(RequestActionReceiver.EXTRA_ID, id)
            .putExtra(RequestActionReceiver.EXTRA_APPROVE, approve)
        return PendingIntent.getBroadcast(
            ctx, notifId(id) * 2 + if (approve) 1 else 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** Handles Approve / Deny taps on a request notification. */
class RequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val code = Prefs.getPairCode(context) ?: return
        RequestNotifier.cancel(context, id)
        // Keep the process alive until the database confirms — but never past
        // Android's broadcast deadline. If the phone is offline the answer goes
        // through when it reconnects, as long as the alert service is running;
        // the request also stays answerable from the dashboard.
        val pending = goAsync()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (done.compareAndSet(false, true)) pending.finish() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 8_000L)
        FirebaseRepo.decideRequest(code, id, approve) { _ -> finish() }
    }

    companion object {
        const val EXTRA_ID = "request_id"
        const val EXTRA_APPROVE = "approve"
    }
}
