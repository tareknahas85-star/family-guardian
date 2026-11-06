package com.tarek.guardian.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/**
 * All parent<->child sync goes through Realtime Database under /devices/{pairCode}.
 * The pairing code is the shared key that links the two installs.
 *
 * ## Access control
 * Security rules (see `database.rules.json` at the repo root) only let a device
 * touch `/devices/{code}` once its Firebase UID is listed under
 * `/devices/{code}/members`. A UID may add itself only when either
 *   * it is creating a brand-new code and naming itself `owner` in the same
 *     atomic write ([createCode] — parent only), or
 *   * `pairingOpenUntil` is in the future (a window only the owner can open).
 *
 * A child can therefore never "grab" a code first and lock the parent out —
 * which is exactly what used to happen when the child paired before the
 * parent's own claim reached the server. The parent only ever shows a code
 * after the server has confirmed it owns it.
 *
 * Flow: parent calls [createCode] once (it also opens the first pairing
 * window), child calls [claimDevice] while the window is open. Call
 * [claimDevice] on every start; it is a no-op once the UID is a member.
 */
object FirebaseRepo {
    private const val TAG = "FirebaseRepo"

    /** How long a pairing window stays open. Rules cap this at 1 hour. */
    const val PAIRING_WINDOW_MS = 15 * 60 * 1000L

    private val db get() = FirebaseDatabase.getInstance()
    private fun root(code: String) = db.getReference("devices").child(code)

    // ---------- Membership / pairing ----------

    /** How long to wait for the server before calling a pairing step failed. */
    private const val SERVER_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())

    /**
     * Adds this device's UID to the code's member list. Safe to call repeatedly.
     *
     * Waits for anonymous sign-in via [Auth.withUid] first. Calling this before
     * sign-in completed used to fail silently and leave the app with no database
     * listeners at all, which looked exactly like "paired but nothing happens".
     */
    fun claimDevice(code: String, onResult: ((Boolean) -> Unit)? = null) =
        claimDeviceDetailed(code) { ok, _ -> onResult?.invoke(ok) }

    /**
     * Same as [claimDevice] but also reports *why* it failed, so the screen can
     * say something more useful than "Pairing failed".
     *
     * Error values: [ERR_AUTH] (anonymous sign-in failed), [ERR_DENIED] (the
     * rules refused — wrong code or pairing window closed), [ERR_TIMEOUT] (no
     * answer from the server — offline, or wrong database URL), or the raw
     * Firebase message for anything else.
     */
    fun claimDeviceDetailed(code: String, onResult: (ok: Boolean, error: String?) -> Unit) {
        if (code.isBlank()) { onResult(false, "empty code"); return }
        val once = Once(onResult)
        Auth.withUid(
            onFailed = { msg ->
                Log.e(TAG, "claimDevice($code): no uid — $msg")
                once(false, "$ERR_AUTH: $msg")
            }
        ) { u ->
            // RTDB never fails a write while offline — it queues it and waits
            // forever, so without this the screen sat on "Pairing…" for good.
            main.postDelayed({ once(false, ERR_TIMEOUT) }, SERVER_TIMEOUT_MS)
            root(code).child("members").child(u).setValue(true)
                .addOnSuccessListener { once(true, null) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "claimDevice failed for $code: ${e.message}")
                    once(false, describe(e))
                }
        }
    }

    /**
     * Parent-side: lets a second device join for the next [PAIRING_WINDOW_MS].
     *
     * Three things used to make this fail silently while the parent's screen
     * still said "the child can join now":
     *  * it wrote before anonymous sign-in had finished (rejected: auth == null);
     *  * it did not make sure the parent itself was a member first;
     *  * the deadline came from the phone's own clock, but the rules compare it
     *    with the *server's* clock — a phone running 15+ minutes slow opened a
     *    window that was already closed.
     * Now it signs in, claims, corrects for clock skew, and reports the result.
     */
    fun openPairing(code: String, onResult: ((ok: Boolean, error: String?) -> Unit)? = null) {
        if (code.isBlank()) { onResult?.invoke(false, "empty code"); return }
        claimDeviceDetailed(code) { claimed, err ->
            if (!claimed) { onResult?.invoke(false, err); return@claimDeviceDetailed }
            val once = Once<Boolean, String?> { ok, e -> onResult?.invoke(ok, e) }
            main.postDelayed({ once(false, ERR_TIMEOUT) }, SERVER_TIMEOUT_MS)
            // The documented way to read clock skew: a single-value listener on
            // .info/serverTimeOffset, answered locally without a round-trip.
            db.getReference(".info/serverTimeOffset")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) = write(s.getValue(Long::class.java) ?: 0L)
                    override fun onCancelled(e: DatabaseError) = write(0L)
                    fun write(offset: Long) {
                        val until = System.currentTimeMillis() + offset + PAIRING_WINDOW_MS
                        root(code).child("pairingOpenUntil").setValue(until)
                            .addOnSuccessListener { once(true, null) }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "openPairing failed: ${e.message}")
                                once(false, describe(e))
                            }
                    }
                })
        }
    }

    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CREATE_ATTEMPTS = 5

    private fun randomCode() = (1..6).map { CODE_CHARS.random() }.joinToString("")

    /**
     * Parent-side: creates a fresh code that this device owns, in ONE atomic
     * write — `owner`, its own membership and a first pairing window together.
     *
     * The code is only returned after the server accepted that write, so the
     * parent never shows a code it does not own yet. If the random code already
     * exists the rules refuse it and another one is tried.
     */
    fun createCode(onResult: (code: String?, error: String?) -> Unit) {
        val once = Once<String?, String?> { c, e -> onResult(c, e) }
        Auth.withUid(onFailed = { msg -> once(null, "$ERR_AUTH: $msg") }) { u ->
            main.postDelayed({ once(null, ERR_TIMEOUT) }, SERVER_TIMEOUT_MS * CREATE_ATTEMPTS)
            db.getReference(".info/serverTimeOffset")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) = attempt(s.getValue(Long::class.java) ?: 0L, 1)
                    override fun onCancelled(e: DatabaseError) = attempt(0L, 1)
                    fun attempt(offset: Long, n: Int) {
                        val code = randomCode()
                        val until = System.currentTimeMillis() + offset + PAIRING_WINDOW_MS
                        root(code).updateChildren(
                            mapOf(
                                "owner" to u,
                                "members/$u" to true,
                                "pairingOpenUntil" to until
                            )
                        ).addOnSuccessListener { once(code, null) }
                         .addOnFailureListener { e ->
                             val err = describe(e)
                             Log.e(TAG, "createCode attempt $n ($code) failed: ${e.message}")
                             if (err == ERR_DENIED && n < CREATE_ATTEMPTS) attempt(offset, n + 1)
                             else once(null, err)
                         }
                    }
                })
        }
    }

    /**
     * Parent-side, for codes made before owners existed: records this device as
     * owner if nobody is yet. The rules refuse it silently if an owner exists.
     */
    fun ensureOwner(code: String) {
        val u = Auth.uid ?: return
        root(code).child("owner").get().addOnSuccessListener { s ->
            if (!s.exists()) root(code).child("owner").setValue(u)
        }
    }

    const val ERR_AUTH = "AUTH"
    const val ERR_DENIED = "DENIED"
    const val ERR_TIMEOUT = "TIMEOUT"

    private fun describe(e: Exception): String {
        val code = (e as? DatabaseException)?.message ?: e.message ?: "unknown"
        return if (code.contains("permission", ignoreCase = true)) ERR_DENIED else code
    }

    /** Lets a callback fire only once, whichever of result/timeout comes first. */
    private class Once<A, B>(private val f: (A, B) -> Unit) {
        private var done = false
        operator fun invoke(a: A, b: B) {
            main.post { if (!done) { done = true; f(a, b) } }
        }
    }

    fun closePairing(code: String) {
        if (code.isBlank()) return
        root(code).child("pairingOpenUntil").setValue(0L)
    }

    // ---------- Policy ----------
    fun setPolicy(code: String, policy: Policy) = root(code).child("policy").setValue(policy)

    fun updatePolicy(code: String, updates: Map<String, Any?>) =
        root(code).child("policy").updateChildren(updates)

    fun listenPolicy(code: String, onChange: (Policy) -> Unit): ValueEventListener {
        val ref = root(code).child("policy")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                // getValue() throws (doesn't return null) when a single field can't
                // be deserialized — a stale value left over from before a schema
                // change, for instance. This callback used to be unguarded, so that
                // exception crashed the whole long-running MonitorService process,
                // taking every other listener (commands included) down with it —
                // right on the pattern of "it broke exactly when the Policy schema
                // grew new fields." A bad update here must never be allowed to
                // propagate; skip it and keep whatever policy is already applied.
                val p = try {
                    s.getValue(Policy::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "policy/$code failed to parse — ignoring this update: ${e.message}")
                    null
                }
                if (p != null) onChange(p)
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [policy/$code]: ${e.message}")
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    // ---------- Commands ----------
    fun pushCommand(code: String, cmd: Command) {
        val ref = root(code).child("commands").push()
        cmd.id = ref.key ?: ""
        cmd.ts = System.currentTimeMillis()
        ref.setValue(cmd)
    }

    fun listenCommands(code: String, onCmd: (Command) -> Unit): ChildEventListener {
        val ref = root(code).child("commands")
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                // Same reasoning as listenPolicy: one malformed command must never
                // be allowed to throw out of this callback and take the rest of
                // the service's listeners down with it.
                val c = try {
                    s.getValue(Command::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "commands/$code: one command failed to parse, skipping: ${e.message}")
                    null
                } ?: return
                if (!c.done) onCmd(c)
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [commands/$code]: ${e.message}")
            }
        }
        ref.addChildEventListener(l)
        return l
    }

    fun markDone(code: String, cmdId: String) {
        if (cmdId.isNotEmpty()) root(code).child("commands").child(cmdId).child("done").setValue(true)
    }

    // ---------- Alerts (child -> parent, high priority) ----------

    fun pushAlert(code: String, alert: Alert) {
        if (code.isBlank()) return
        val ref = root(code).child("alerts").push()
        alert.id = ref.key ?: ""
        if (alert.ts == 0L) alert.ts = System.currentTimeMillis()
        ref.setValue(alert)
    }

    /** Fires for every alert added, including the backlog already stored. */
    fun listenAlerts(code: String, onAlert: (Alert) -> Unit): ChildEventListener {
        val ref = root(code).child("alerts")
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                s.getValue(Alert::class.java)?.let(onAlert)
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [alerts/$code]: ${e.message}")
            }
        }
        ref.addChildEventListener(l)
        return l
    }

    fun markAlertSeen(code: String, alertId: String) {
        if (alertId.isNotEmpty()) root(code).child("alerts").child(alertId).child("seen").setValue(true)
    }

    // ---------- Live activity feed (child -> parent) ----------

    /**
     * Appends one event. Capped by [trimEvents] so the feed cannot grow forever
     * on the free plan.
     */
    fun pushEvent(code: String, event: ActivityEvent) {
        if (code.isBlank()) return
        val ref = root(code).child("events").push()
        event.id = ref.key ?: ""
        if (event.ts == 0L) event.ts = System.currentTimeMillis()
        ref.setValue(event)
    }

    /** Newest [limit] events, oldest first within the window. */
    fun listenEvents(
        code: String,
        limit: Int = 100,
        onEvent: (ActivityEvent) -> Unit
    ): ChildEventListener {
        val ref = root(code).child("events").limitToLast(limit)
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                s.getValue(ActivityEvent::class.java)?.let(onEvent)
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [events/$code]: ${e.message}")
            }
        }
        ref.addChildEventListener(l)
        return l
    }

    /**
     * Deletes all but the newest [keep] events. Called occasionally by the child;
     * without it the feed would grow without bound on a free database.
     */
    fun trimEvents(code: String, keep: Int = 300) {
        if (code.isBlank()) return
        root(code).child("events").orderByChild("ts").limitToLast(keep).get()
            .addOnSuccessListener { newest ->
                val keepIds = newest.children.mapNotNull { it.key }.toSet()
                root(code).child("events").get().addOnSuccessListener { all ->
                    val doomed = all.children.mapNotNull { it.key }.filter { it !in keepIds }
                    if (doomed.isEmpty()) return@addOnSuccessListener
                    val updates = doomed.associate { "$it" to null as Any? }
                    root(code).child("events").updateChildren(updates)
                }
            }
            .addOnFailureListener { e -> Log.e(TAG, "trimEvents failed: ${e.message}") }
    }

    // ---------- Reports (child -> parent) ----------
    fun reportUsage(code: String, date: String, pkg: String, minutes: Int) {
        // Firebase keys cannot contain '.', so store package with '_'.
        root(code).child("reports").child("usage").child(date)
            .child(pkg.replace('.', '_')).setValue(minutes)
    }

    fun reportCall(code: String, rec: CallRecord) {
        root(code).child("reports").child("calls").push().setValue(rec)
    }

    fun reportLocation(code: String, lat: Double, lng: Double) {
        val m = mapOf("lat" to lat, "lng" to lng, "ts" to System.currentTimeMillis())
        root(code).child("reports").child("location").child("latest").setValue(m)
    }

    fun setChildInfo(
        code: String, model: String, batteryPct: Int = -1, charging: Boolean = false,
        adminActive: Boolean? = null, vpnReady: Boolean? = null, accessibilityActive: Boolean? = null
    ) {
        val m = HashMap<String, Any>()
        m["model"] = model
        m["role"] = "child"
        m["lastSeen"] = System.currentTimeMillis()
        if (batteryPct in 0..100) {
            m["battery"] = batteryPct
            m["charging"] = charging
        }
        // Whether LOCK_NOW / BLOCK_INTERNET / app-blocking can actually do
        // anything on this device right now — see the comment on
        // GuardianState.Snapshot.
        adminActive?.let { m["adminActive"] = it }
        vpnReady?.let { m["vpnReady"] = it }
        accessibilityActive?.let { m["accessibilityActive"] = it }
        root(code).child("info").updateChildren(m)
    }

    /**
     * The child's launchable apps, so the parent can pick what to restrict
     * without typing package names. Written once an hour; the list rarely moves.
     */
    fun reportInstalledApps(code: String, apps: Map<String, String>) {
        if (code.isBlank() || apps.isEmpty()) return
        // Firebase keys cannot contain a dot.
        val safe = apps.entries.associate { (pkg, label) -> pkg.replace('.', '_') to label }
        root(code).child("reports").child("apps").setValue(safe)
    }

    /** Parent side: package name -> app label. */
    fun listenInstalledApps(code: String, onData: (Map<String, String>) -> Unit): ValueEventListener {
        val ref = root(code).child("reports").child("apps")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val out = HashMap<String, String>()
                for (c in s.children) {
                    val pkg = c.key?.replace('_', '.') ?: continue
                    out[pkg] = c.getValue(String::class.java) ?: pkg
                }
                onData(out)
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [apps/$code]: ${e.message}")
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    /**
     * Live health of the child device: model, when it last checked in, and
     * whether its internet is currently off. Lets the parent screen tell
     * "nothing happened" apart from "nothing is connected".
     */
    fun listenChildInfo(
        code: String,
        onData: (model: String, lastSeen: Long, internetBlocked: Boolean,
                 battery: Int, charging: Boolean, adminActive: Boolean, vpnReady: Boolean,
                 accessibilityActive: Boolean) -> Unit
    ): ValueEventListener {
        val ref = root(code)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val info = s.child("info")
                onData(
                    info.child("model").getValue(String::class.java) ?: "",
                    info.child("lastSeen").getValue(Long::class.java) ?: 0L,
                    s.child("policy").child("internetBlocked").getValue(Boolean::class.java) ?: false,
                    info.child("battery").getValue(Int::class.java) ?: -1,
                    info.child("charging").getValue(Boolean::class.java) ?: false,
                    info.child("adminActive").getValue(Boolean::class.java) ?: false,
                    info.child("vpnReady").getValue(Boolean::class.java) ?: false,
                    info.child("accessibilityActive").getValue(Boolean::class.java) ?: false
                )
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [info/$code]: ${e.message}")
                onData("", 0L, false, -1, false, false, false, false)
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    /** Live last-known position of the child device. */
    fun listenLocation(
        code: String,
        onData: (lat: Double, lng: Double, ts: Long) -> Unit
    ): ValueEventListener {
        val ref = root(code).child("reports").child("location").child("latest")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onData(
                    s.child("lat").getValue(Double::class.java) ?: 0.0,
                    s.child("lng").getValue(Double::class.java) ?: 0.0,
                    s.child("ts").getValue(Long::class.java) ?: 0L
                )
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [location/$code]: ${e.message}")
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    /** Remembers which side of each safe zone the child was on, to detect crossings. */
    fun setZoneState(code: String, zoneId: String, inside: Boolean) {
        root(code).child("state").child("zones").child(zoneId).setValue(inside)
    }

    // ---------- Reports (parent view) ----------
    fun listenUsageToday(code: String, date: String, onData: (Map<String, Int>) -> Unit): ValueEventListener {
        val ref = root(code).child("reports").child("usage").child(date)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val map = HashMap<String, Int>()
                for (child in s.children) {
                    val v = child.getValue(Int::class.java) ?: 0
                    map[child.key?.replace('_', '.') ?: ""] = v
                }
                onData(map)
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [usage/$code/$date]: ${e.message}")
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    /** One-shot read of a single day's usage — used by the weekly digest. */
    fun readUsageForDate(code: String, date: String, onData: (Map<String, Int>) -> Unit) {
        root(code).child("reports").child("usage").child(date)
            .get()
            .addOnSuccessListener { s ->
                val map = HashMap<String, Int>()
                for (child in s.children) {
                    map[child.key?.replace('_', '.') ?: ""] = child.getValue(Int::class.java) ?: 0
                }
                onData(map)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "readUsageForDate failed: ${e.message}")
                onData(emptyMap())
            }
    }

    // ---------- Requests: extra time & task rewards ----------

    private fun today(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** Child side: ask the parent for something. */
    fun pushRequest(code: String, req: ChildRequest, onDone: ((Boolean) -> Unit)? = null) {
        if (code.isBlank()) { onDone?.invoke(false); return }
        val ref = root(code).child("requests").push()
        req.id = ref.key ?: ""
        req.ts = System.currentTimeMillis()
        req.status = ChildRequest.PENDING
        ref.setValue(req)
            .addOnSuccessListener { onDone?.invoke(true) }
            .addOnFailureListener { onDone?.invoke(false) }
    }

    /** Both sides: the latest requests, newest last. */
    fun listenRequests(code: String, onData: (List<ChildRequest>) -> Unit): ValueEventListener {
        val ref = root(code).child("requests").orderByChild("ts").limitToLast(30)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onData(s.children.mapNotNull { c ->
                    c.getValue(ChildRequest::class.java)?.also { if (it.id.isBlank()) it.id = c.key ?: "" }
                })
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [requests/$code]: ${e.message}")
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    fun detachRequests(code: String, l: ValueEventListener) =
        root(code).child("requests").orderByChild("ts").limitToLast(30).removeEventListener(l)

    fun detachPolicy(code: String, l: ValueEventListener) =
        root(code).child("policy").removeEventListener(l)

    fun detachTrack(code: String, date: String, l: ValueEventListener) =
        root(code).child("reports").child("track").child(date).removeEventListener(l)

    /**
     * Parent side: approve or deny. Two transactions, so tapping Approve twice
     * (in the app and in the notification) can never grant the minutes twice:
     * the first one flips the status from pending, the second sees it decided.
     */
    fun decideRequest(code: String, requestId: String, approve: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        if (code.isBlank() || requestId.isBlank()) { onDone?.invoke(false); return }
        var minutes = 0
        root(code).child("requests").child(requestId).runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                // First pass can run on an empty local cache; returning success
                // here lets the server answer with the real node and retry.
                if (d.value == null) return Transaction.success(d)
                val status = d.child("status").getValue(String::class.java) ?: return Transaction.abort()
                if (status != ChildRequest.PENDING) return Transaction.abort()
                minutes = d.child("minutes").getValue(Int::class.java) ?: 0
                d.child("status").value = if (approve) ChildRequest.APPROVED else ChildRequest.DENIED
                d.child("decidedTs").value = System.currentTimeMillis()
                return Transaction.success(d)
            }
            override fun onComplete(e: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                if (e != null || !committed) { onDone?.invoke(false); return }
                if (!approve || minutes <= 0) { onDone?.invoke(true); return }
                addBonusMinutes(code, minutes, onDone)
            }
        })
    }

    /** Adds to today's bonus; a bonus from an earlier day is replaced, not added to. */
    fun addBonusMinutes(code: String, minutes: Int, onDone: ((Boolean) -> Unit)? = null) {
        val day = today()
        root(code).child("policy").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                val date = d.child("bonusDate").getValue(String::class.java) ?: ""
                val current = if (date == day) d.child("bonusMinutes").getValue(Int::class.java) ?: 0 else 0
                d.child("bonusDate").value = day
                d.child("bonusMinutes").value = (current + minutes).coerceAtMost(12 * 60)
                return Transaction.success(d)
            }
            override fun onComplete(e: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                onDone?.invoke(e == null && committed)
            }
        })
    }

    // ---------- Location timeline ----------

    /** Child side: one point in today's track. Throttling is the caller's job. */
    fun reportLocationPoint(code: String, lat: Double, lng: Double) {
        if (code.isBlank()) return
        root(code).child("reports").child("track").child(today()).push()
            .setValue(LocPoint(lat, lng, System.currentTimeMillis()))
    }

    /** Parent side: a day's track, oldest first. */
    fun listenTrack(code: String, date: String, onData: (List<LocPoint>) -> Unit): ValueEventListener {
        val ref = root(code).child("reports").child("track").child(date)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onData(s.children.mapNotNull { it.getValue(LocPoint::class.java) }.sortedBy { it.ts })
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "DB Error [track/$code/$date]: ${e.message}")
            }
        }
        ref.addValueEventListener(l)
        return l
    }

    /** Keeps the last [days] days of track and drops older ones (free-plan storage). */
    fun trimTrack(code: String, days: Int = 7) {
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -days) }
        val cutoff = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(cal.time)
        val ref = root(code).child("reports").child("track")
        ref.orderByKey().endAt(cutoff).get().addOnSuccessListener { s ->
            s.children.forEach { it.ref.removeValue() }
        }
    }
}
