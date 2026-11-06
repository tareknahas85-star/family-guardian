package com.tarek.guardian.vpn

import com.tarek.guardian.L10n
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tarek.guardian.App
import com.tarek.guardian.R
import com.tarek.guardian.ui.RoleSelectActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Local VPN with two jobs: switching the child's internet **off**, and
 * **safe browsing** (filtering what the internet may resolve).
 *
 * ## Block mode
 * A tun interface captures every packet and drops it, so nothing reaches the
 * network, on Wi-Fi and mobile data alike.
 *
 * ## Filter mode
 * Only DNS goes through the tunnel: the VPN announces a private DNS address
 * ([FAKE_DNS]) and routes just that one address. Each lookup is answered by
 * forwarding it to CleanBrowsing's free Family filter, which refuses adult,
 * gambling and proxy/VPN sites and forces SafeSearch on Google, Bing and
 * DuckDuckGo plus Restricted Mode on YouTube. Anything in the parent's own
 * [blockedDomains] list is refused here, before it leaves the phone. All other
 * traffic goes out normally, so speed is unaffected.
 *
 * ## Never trap ourselves
 * [Builder.addDisallowedApplication] keeps this app — and Google Play Services,
 * which carries push messages — outside the tunnel in both modes, so the
 * "allow internet" command always arrives. Block mode also has a fail-open
 * watchdog: if the monitor service stops confirming the block for
 * [MAX_BLOCK_MS], the internet opens by itself. A parent must never be able to
 * leave a child with a permanently dead phone.
 */
class FilterVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var reader: Thread? = null
    /** Mode the current tunnel was built for. */
    @Volatile private var activeMode = MODE_OFF
    /**
     * Bumped every time a tunnel is built. A reader thread only keeps going
     * while it belongs to the current tunnel — otherwise, switching filter to
     * block could leave the old thread spinning on a closed descriptor.
     */
    @Volatile private var generation = 0
    private val dnsPool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { desiredMode = MODE_OFF }
            ACTION_START -> { desiredMode = MODE_BLOCK }
            ACTION_FILTER -> { desiredMode = MODE_FILTER }
        }
        internetBlocked = desiredMode == MODE_BLOCK

        if (desiredMode == MODE_OFF) {
            stopTunnel()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, notification())
        if (!running || activeMode != desiredMode) {
            closeTunnel()
            startTunnel(desiredMode)
        }
        return START_STICKY
    }

    // ── Tunnel ────────────────────────────────────────────────────────────────

    private fun startTunnel(mode: Int) {
        try {
            val builder = Builder()
                .setSession("FamilyGuardian")
                .addAddress("10.111.0.2", 32)
                .addDnsServer(FAKE_DNS)
            if (mode == MODE_BLOCK) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                // Filter mode: only DNS is captured, everything else flows freely.
                builder.addRoute(FAKE_DNS, 32)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            }

            // Critical: never trap our own traffic, or the unblock command can
            // never reach this device. Play Services is excluded too so push
            // notifications keep working while the child is offline.
            for (pkg in listOf(packageName, "com.google.android.gms")) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "could not exclude $pkg: ${e.message}")
                }
            }

            tun = builder.establish()
            if (tun == null) {
                Log.e(TAG, "establish() returned null — VPN permission missing?")
                desiredMode = MODE_OFF
                internetBlocked = false
                stopTunnel()
                return
            }

            activeMode = mode
            blockedSince = if (mode == MODE_BLOCK) System.currentTimeMillis() else 0L
            running = true
            val gen = ++generation
            reader = thread(start = true, name = "vpn-reader") { loop(mode, gen) }
            Log.d(TAG, "tunnel up, mode=$mode")
        } catch (e: Exception) {
            Log.e(TAG, "startTunnel failed: ${e.message}")
            desiredMode = MODE_OFF
            internetBlocked = false
            stopTunnel()
        }
    }

    private fun loop(mode: Int, gen: Int) {
        val fd = tun?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buf = ByteArray(32767)
        var failOpen = false
        while (running && gen == generation) {
            try {
                if (mode == MODE_BLOCK && (desiredMode != MODE_BLOCK || watchdogExpired())) {
                    failOpen = watchdogExpired()
                    break
                }
                // Blocks until a packet arrives. Closing the descriptor from
                // closeTunnel() makes this throw, which is how we wake up.
                val n = input.read(buf)
                if (n <= 0) { Thread.sleep(50); continue }
                if (mode == MODE_FILTER) {
                    val packet = buf.copyOf(n)
                    dnsPool.execute { handleDns(packet, output) }
                }
                // Block mode: the packet is simply dropped.
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                // Descriptor closed, or a transient read error.
                if (!running || gen != generation) break
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        if (failOpen && gen == generation) {
            Log.w(TAG, "watchdog expired -> failing open")
            desiredMode = MODE_OFF
            internetBlocked = false
            stopTunnel()
        }
    }

    // ── DNS filter ───────────────────────────────────────────────────────────

    /** Answers one captured packet if it is a DNS query over IPv4/UDP. */
    private fun handleDns(p: ByteArray, out: FileOutputStream) {
        try {
            if (p.size < 28 || (p[0].toInt() shr 4) != 4) return
            val ihl = (p[0].toInt() and 0x0F) * 4
            if (p[9].toInt() != 17 || p.size < ihl + 8 + 12) return          // UDP only
            val dstPort = u16(p, ihl + 2)
            if (dstPort != 53) return
            val query = p.copyOfRange(ihl + 8, p.size)

            val name = DnsPacket.questionName(query)
            val answer = if (name != null && isBlockedLocally(name)) {
                DnsPacket.nxdomain(query)
            } else {
                forward(query) ?: return
            }
            val reply = Ipv4Udp.reply(p, ihl, answer)
            synchronized(out) { out.write(reply) }
        } catch (e: Exception) {
            Log.w(TAG, "dns: ${e.message}")
        }
    }

    private fun isBlockedLocally(name: String): Boolean {
        val n = name.lowercase().trimEnd('.')
        return blockedDomains.any { d ->
            val b = d.lowercase().trim().removePrefix("*.").trimEnd('.')
            b.isNotEmpty() && (n == b || n.endsWith(".$b"))
        }
    }

    /** Sends the query to the first family resolver that answers. */
    private fun forward(query: ByteArray): ByteArray? {
        for (server in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s)                       // keep this socket outside the tunnel
                    s.soTimeout = 3000
                    val addr = InetAddress.getByName(server)
                    s.send(DatagramPacket(query, query.size, addr, 53))
                    val resp = ByteArray(4096)
                    val pkt = DatagramPacket(resp, resp.size)
                    s.receive(pkt)
                    return resp.copyOf(pkt.length)
                }
            } catch (_: Exception) {
                // Try the next resolver.
            }
        }
        return null
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * True when the block has been up too long without the monitor service
     * refreshing [lastPolicyConfirmMs].
     */
    private fun watchdogExpired(): Boolean {
        val since = blockedSince
        if (since == 0L) return false
        val quietFor = System.currentTimeMillis() - maxOf(since, lastPolicyConfirmMs)
        return quietFor > MAX_BLOCK_MS
    }

    /** Closes the interface and its reader, but leaves the service alive. */
    private fun closeTunnel() {
        running = false
        blockedSince = 0L
        activeMode = MODE_OFF
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        reader?.interrupt()
        reader = null
    }

    private fun stopTunnel() {
        closeTunnel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
        Log.d(TAG, "tunnel down")
    }

    override fun onRevoke() {
        // The user turned the VPN off from Android settings. Respect it — this is
        // also the manual escape hatch if the app ever gets stuck again.
        Log.w(TAG, "VPN revoked by user")
        com.tarek.guardian.alerts.TamperReporter.report(this, R.string.tamper_vpn)
        desiredMode = MODE_OFF
        internetBlocked = false
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        dnsPool.shutdownNow()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, App.CH_VPN)
        .setContentTitle(L10n.s(R.string.app_name))
        .setContentText(
            L10n.s(if (desiredMode == MODE_BLOCK) R.string.notif_internet_off else R.string.notif_filtering)
        )
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, RoleSelectActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val TAG = "FilterVpn"
        private const val NOTIF_ID = 2

        const val ACTION_START  = "com.tarek.guardian.VPN_START"
        const val ACTION_STOP   = "com.tarek.guardian.VPN_STOP"
        const val ACTION_FILTER = "com.tarek.guardian.VPN_FILTER"

        private const val MODE_OFF = 0
        private const val MODE_BLOCK = 1
        private const val MODE_FILTER = 2

        /** Private address the phone sends DNS to while filtering. */
        private const val FAKE_DNS = "10.111.0.53"

        /**
         * Free family resolvers, in order: CleanBrowsing Family (adult content,
         * proxies, forced SafeSearch and YouTube Restricted Mode), its second
         * address, then Cloudflare for Families as a backup.
         */
        private val UPSTREAMS = listOf("185.228.168.168", "185.228.169.168", "1.1.1.3", "1.0.0.3")

        /**
         * Longest the internet may stay off without the monitor service
         * confirming the policy. Six hours covers a normal bedtime block while
         * still guaranteeing the phone recovers on its own if something breaks.
         */
        const val MAX_BLOCK_MS = 6 * 60 * 60 * 1000L

        @Volatile private var desiredMode: Int = MODE_OFF
        @Volatile var internetBlocked: Boolean = false
        @Volatile var blockedDomains: HashSet<String> = HashSet()

        /** When the current block started. 0 when not blocking. */
        @Volatile private var blockedSince: Long = 0L

        /** Refreshed by MonitorService each time it confirms the policy. */
        @Volatile private var lastPolicyConfirmMs: Long = 0L

        /** Called once a minute by MonitorService while a block is intended. */
        fun confirmStillBlocked() { lastPolicyConfirmMs = System.currentTimeMillis() }

        /** Turn the internet off. */
        fun block(ctx: Context) {
            desiredMode = MODE_BLOCK
            internetBlocked = true
            send(ctx, ACTION_START, fg = true)
        }

        /** Internet on, through the family DNS filter. No-op if already filtering. */
        fun filter(ctx: Context) {
            if (desiredMode == MODE_FILTER) return
            desiredMode = MODE_FILTER
            internetBlocked = false
            send(ctx, ACTION_FILTER, fg = true)
        }

        /**
         * Internet fully on. Sends a real intent so `onStartCommand` runs and
         * tears the tunnel down — setting the flag alone did nothing.
         */
        fun unblock(ctx: Context) {
            desiredMode = MODE_OFF
            internetBlocked = false
            send(ctx, ACTION_STOP, fg = false)
        }

        private fun send(ctx: Context, action: String, fg: Boolean) {
            val i = Intent(ctx, FilterVpnService::class.java).setAction(action)
            try {
                if (fg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                Log.e(TAG, "$action failed: ${e.message}")
            }
        }
    }
}
