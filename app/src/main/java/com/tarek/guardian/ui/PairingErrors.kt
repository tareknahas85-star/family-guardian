package com.tarek.guardian.ui

import android.content.Context
import com.tarek.guardian.R
import com.tarek.guardian.data.FirebaseRepo

/**
 * Turns a pairing error from [FirebaseRepo.claimDeviceDetailed] /
 * [FirebaseRepo.openPairing] into a sentence a parent can act on.
 * Anything unrecognised is shown raw so it can be reported as-is.
 */
fun pairingErrorText(ctx: Context, err: String?): String = when {
    err == null -> ""
    err == FirebaseRepo.ERR_DENIED -> ctx.getString(R.string.pair_err_denied)
    err == FirebaseRepo.ERR_TIMEOUT -> ctx.getString(R.string.pair_err_timeout)
    err.startsWith(FirebaseRepo.ERR_AUTH) -> ctx.getString(R.string.pair_err_auth) + "\n(" + err + ")"
    else -> err
}
