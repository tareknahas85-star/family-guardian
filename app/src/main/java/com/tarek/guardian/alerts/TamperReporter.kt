package com.tarek.guardian.alerts

import android.content.Context
import com.tarek.guardian.L10n
import com.tarek.guardian.Prefs
import com.tarek.guardian.R
import com.tarek.guardian.data.Alert
import com.tarek.guardian.data.FirebaseRepo

/**
 * Immediate tamper alerts for the moments Android tells us about directly —
 * someone opening the "deactivate device admin" screen (the first step to
 * uninstalling) or switching the VPN off. The once-a-minute check in
 * MonitorService catches everything else.
 */
object TamperReporter {
    fun report(ctx: Context, what: Int) {
        if (Prefs.getRole(ctx) != Prefs.ROLE_CHILD) return
        val code = Prefs.getPairCode(ctx) ?: return
        FirebaseRepo.pushAlert(
            code,
            Alert(
                type = Alert.TAMPER,
                title = L10n.s(R.string.tamper_title),
                body = L10n.s(R.string.tamper_body, "• " + L10n.s(what)),
                ts = System.currentTimeMillis()
            )
        )
    }
}
