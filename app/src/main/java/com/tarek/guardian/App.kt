package com.tarek.guardian

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth

/**
 * Application class — runs once at process start.
 *
 * Firebase is configured from `app/google-services.json`, which points at the
 * real `family-guardian` project. Anonymous Authentication must stay enabled in
 * the Firebase Console — the database rules require `auth != null`, so without
 * it every read and write is rejected.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
        LocaleManager.restore(this)

        // ── Notification channels ─────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Background monitoring (low priority — persistent foreground notification)
            nm.createNotificationChannel(
                NotificationChannel(CH_MONITOR, L10n.s(R.string.ch_supervision), NotificationManager.IMPORTANCE_LOW)
            )
            // VPN / internet filter indicator (min priority)
            nm.createNotificationChannel(
                NotificationChannel(CH_VPN, L10n.s(R.string.ch_filter), NotificationManager.IMPORTANCE_MIN)
            )
            // Live feed — ordinary activity. DEFAULT rather than HIGH so a busy
            // child does not produce a stream of heads-up popovers; the parent can
            // still raise it per-channel in system settings.
            nm.createNotificationChannel(
                NotificationChannel(CH_ACTIVITY, L10n.s(R.string.ch_activity), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = L10n.s(R.string.ch_activity_desc)
                    setShowBadge(true)
                }
            )
            // Parent alerts — high priority, heads-up style
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERTS, L10n.s(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = L10n.s(R.string.ch_alerts_desc)
                }
            )
            // Child's requests for more time / task rewards — answered from the shade.
            nm.createNotificationChannel(
                NotificationChannel(CH_REQUESTS, L10n.s(R.string.ch_requests), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = L10n.s(R.string.ch_requests_desc)
                }
            )
        }

        // ── Anonymous Firebase Auth ───────────────────────────────────────────
        // Ensures every install has a Firebase UID even before the user logs in.
        // The database rules key off this UID: a device may only touch
        // /devices/{code} once its UID is listed under that code's `members`.
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    Log.d(TAG, "Anonymous auth OK — uid=${result.user?.uid}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Auth failed: ${e.message}")
                }
        }
    }

    companion object {
        private const val TAG = "FamilyGuardian"

        const val CH_MONITOR = "monitor"
        const val CH_VPN     = "vpn_filter"
        const val CH_ALERTS  = "alerts"          // high-priority parent notifications
        const val CH_ACTIVITY = "activity"       // ordinary live-feed events
        const val CH_REQUESTS = "requests"       // child asks for time / rewards
    }
}
