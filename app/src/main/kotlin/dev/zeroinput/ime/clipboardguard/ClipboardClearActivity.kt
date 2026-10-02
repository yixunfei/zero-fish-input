package dev.zeroinput.ime.clipboardguard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.security.AuthenticationGrant
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** A notification can open this page, but only foreground user actions can clear. */
class ClipboardClearActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val active = AtomicBoolean(true)
    private val focused = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var ticket = ""
    private var resumed = false
    private var busy = false
    private var authenticating = false
    private var grant: AuthenticationGrant? = null
    private var authentication: AuthenticationBroker.RequestHandle? = null
    private var observer: AutoCloseable? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private val expire = Runnable { cancelAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ticket = intent.getStringExtra(EXTRA_TICKET).orEmpty()
        intent = Intent()
        setResult(RESULT_CANCELED)
        if (savedInstanceState != null || !valid()) {
            finish()
            return
        }
        observer = graph.clipboardGuard.observe { if (!valid()) cancelAndFinish() }
        if (isFinishing) return
        showConfirmation()
        main.postDelayed(expire, 60_000L)
    }

    override fun onResume() { super.onResume(); resumed = true }

    override fun onPause() {
        resumed = false
        focused.set(false)
        if (!authenticating) cancelAndFinish()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) focused.set(false)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); cancelAndFinish() }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun valid(): Boolean {
        val state = graph.clipboardGuard.state
        return active.get() && ticket.isNotEmpty() && state.ticket?.id == ticket &&
            graph.clipboardGuardPreferences.options.listening &&
            graph.clipboardGuardPreferences.options == state.options &&
            (state.options.clearMode != ClipboardClearMode.NONE || state.ticket.userRequested)
    }

    private fun showConfirmation() {
        val requiresAuthentication = graph.clipboardGuardPreferences.options.authenticate
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clipboard_guard_clear_current)
            .setMessage(R.string.clipboard_guard_confirmation_warning)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelAndFinish() }
            .setPositiveButton(if (requiresAuthentication) R.string.clipboard_guard_authenticate_action else R.string.clipboard_guard_confirm_action, null)
            .setOnCancelListener { cancelAndFinish() }
            .create().also { alert ->
                alert.setOnShowListener {
                    alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        filterTouchesWhenObscured = true
                        setOnClickListener {
                            if (canConfirmClear(resumed, alert.window?.decorView?.hasWindowFocus() == true, busy, valid())) {
                                focused.set(true)
                                alert.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
                                    if (!hasFocus) focused.set(false)
                                }
                                if (requiresAuthentication && grant == null) authenticate() else clear()
                            }
                        }
                    }
                }
                alert.show()
                alert.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
    }

    private fun authenticate() {
        busy = true
        authenticating = true
        authentication = AuthenticationBroker.requestCancellable(this, R.string.clipboard_guard_authentication_title) { result ->
            authenticating = false
            authentication = null
            busy = false
            if (!valid() || result == null) cancelAndFinish() else {
                grant = result
                dialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setText(R.string.clipboard_guard_confirm_action)
                main.removeCallbacks(expire)
                main.postDelayed(expire, 30_000L)
            }
        }
    }

    private fun clear() {
        busy = true
        val authorization = grant
        grant = null
        val owner = WeakReference(this)
        val requestActive = active
        val requestFocused = focused
        graph.clipboardGuard.clear(ticket, authorization, { requestActive.get() && requestFocused.get() }) { outcome ->
            owner.get()?.let { activity ->
                if (activity.resumed && !activity.isFinishing) {
                    Toast.makeText(activity, when (outcome) {
                        ClipboardClearResult.CLEARED -> R.string.clipboard_guard_cleared
                        ClipboardClearResult.FAILED -> R.string.clipboard_guard_failed
                        else -> R.string.clipboard_guard_expired
                    }, Toast.LENGTH_SHORT).show()
                }
                activity.cancelAndFinish()
            }
        }
    }

    private fun cancelAndFinish() { cancel(); finish() }

    private fun cancel() {
        active.set(false)
        focused.set(false)
        grant = null
        authentication?.close()
        authentication = null
        observer?.close()
        observer = null
        main.removeCallbacks(expire)
        dialog?.dismiss()
        dialog = null
    }

    internal companion object {
        const val EXTRA_TICKET = "dev.zeroinput.ime.clipboardguard.TICKET"
    }
}

internal fun canConfirmClear(resumed: Boolean, windowHasFocus: Boolean, busy: Boolean, valid: Boolean): Boolean =
    resumed && windowHasFocus && !busy && valid
