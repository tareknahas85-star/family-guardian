package com.tarek.guardian.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Android allows one VPN at a time; this tells us when someone else's (e.g. Proton) holds it. */
object VpnState {
    fun otherVpnActive(ctx: Context): Boolean {
        if (FilterVpnService.up) return false
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) { false }
    }
}