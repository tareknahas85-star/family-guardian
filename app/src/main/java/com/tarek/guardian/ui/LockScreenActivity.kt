package com.tarek.guardian.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tarek.guardian.L10n
import com.tarek.guardian.Prefs
import com.tarek.guardian.R
import com.tarek.guardian.calls.CallPolicyStore
import com.tarek.guardian.calls.ContactsLookup
import com.tarek.guardian.calls.NumberUtils
import com.tarek.guardian.data.LockCalls
import com.tarek.guardian.monitor.AppBlockService
import com.tarek.guardian.monitor.SosReporter

/**
 * What the child sees while the whole device is locked and the parent allows
 * calls: one button per trusted number (the priority and allowed numbers from
 * call filtering), optionally the phone app, and SOS. Everything else stays
 * blocked by [AppBlockService]. Closes itself the moment the lock is lifted.
 */
class LockScreenActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingNumber: String? = null

    private val watch = object : Runnable {
        override fun run() {
            if (!AppBlockService.deviceLocked() || AppBlockService.lockCalls == LockCalls.OFF) {
                finish()
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        setContentView(R.layout.activity_lock)
        // Back must not reveal whatever is underneath.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(watch)
        handler.post(watch)
    }

    override fun onPause() {
        handler.removeCallbacks(watch)
        super.onPause()
    }

    private fun render() {
        findViewById<TextView>(R.id.tvLockTitle).text = L10n.s(R.string.lock_title)
        findViewById<TextView>(R.id.tvLockSub).text = L10n.s(R.string.lock_subtitle)

        val host = findViewById<LinearLayout>(R.id.lockNumbersHost)
        host.removeAllViews()
        val snap = CallPolicyStore.load(this)
        val seen = HashSet<String>()
        val numbers = (snap.priority.toList() + snap.allowed.filter { !NumberUtils.matchesAny(it, snap.blocked) })
            .filter { it.isNotBlank() && seen.add(NumberUtils.key(it)) }
        for (n in numbers) {
            val btn = MaterialButton(this)
            val name = try { ContactsLookup.displayName(this, n) } catch (_: Exception) { null }
            btn.text = "\uD83D\uDCDE  " + (name ?: NumberUtils.display(n))
            btn.isAllCaps = false
            btn.textSize = 17f
            btn.minHeight = (56 * resources.displayMetrics.density).toInt()
            btn.setOnClickListener { call(n) }
            host.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            )
        }
        val empty = findViewById<TextView>(R.id.tvLockEmpty)
        empty.text = L10n.s(R.string.lock_no_numbers)
        empty.visibility = if (numbers.isEmpty()) View.VISIBLE else View.GONE

        val open = findViewById<MaterialButton>(R.id.btnLockOpenPhone)
        open.text = L10n.s(R.string.lock_open_phone)
        open.visibility = if (AppBlockService.lockCalls == LockCalls.ALL) View.VISIBLE else View.GONE
        open.setOnClickListener {
            AppBlockService.grace(8000L)
            try {
                startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }

        val sos = findViewById<MaterialButton>(R.id.btnLockSos)
        sos.text = L10n.s(R.string.sos_button)
        sos.visibility = if (AppBlockService.sosOnLock) View.VISIBLE else View.GONE
        sos.setOnClickListener {
            val code = Prefs.getPairCode(this)
            if (code.isNullOrEmpty()) {
                Toast.makeText(this, L10n.s(R.string.sos_unpaired), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, L10n.s(R.string.sos_sent_parent), Toast.LENGTH_LONG).show()
                SosReporter.trigger(this, code)
            }
        }
    }

    private fun call(number: String) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // The permission dialog belongs to another package; let it through briefly.
            pendingNumber = number
            AppBlockService.grace(30_000L)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        // Covers the moment between placing the call and the system reporting it.
        AppBlockService.grace(8000L)
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        } catch (e: Exception) {
            Toast.makeText(this, L10n.s(R.string.lock_call_perm), Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALL) return
        val n = pendingNumber
        pendingNumber = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && n != null) call(n)
        else Toast.makeText(this, L10n.s(R.string.lock_call_perm), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQ_CALL = 7
    }
}