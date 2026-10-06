package dev.zeroinput.ime.clipboard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.security.AuthenticationGrant
import java.lang.ref.WeakReference
import java.util.concurrent.RejectedExecutionException

/** Untrusted input only: this component never lists vault contents or returns data. */
open class ClipboardImportActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread("zeroinput-text-import", 1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var request: ClipboardImportRequest? = null
    private var screen: ClipboardImportView? = null
    private var authentication: AuthenticationBroker.RequestHandle? = null
    private var grant: AuthenticationGrant? = null
    private var settingsListener: AutoCloseable? = null
    private var enabling = false
    private var resumed = false
    private var vaultGeneration = 0L
    private val expire = Runnable { discardAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)
        val incoming = intent
        intent = Intent()
        val draft = if (savedInstanceState == null) receiveDraft(incoming) else null
        request = draft?.request
        incoming.replaceExtras(null as Bundle?)
        incoming.clipData = null
        if (request == null) {
            showMessage(R.string.clipboard_import_invalid)
            finish()
            return
        }
        vaultGeneration = checkNotNull(draft).generation
        screen = ClipboardImportView(this).also {
            it.onCancel = ::discardAndFinish
            it.onAction = ::performAction
            it.onAiAction = ::importAiContext
            it.showReview(requireNotNull(request).length, graph.settings.secureClipboardEnabled)
            setContentView(it)
        }
        settingsListener = graph.settings.addChangeListener {
            if (!enabling) discardAndFinish()
        }
        mainHandler.postDelayed(expire, DRAFT_TIMEOUT_MILLIS)
    }

    internal open fun receiveDraft(intent: Intent): ClipboardImportDraft? =
        ClipboardImportIntent.parse(intent)?.let { ClipboardImportDraft(it, graph.secureClipboard.captureGeneration()) }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (request?.phase == ClipboardImportRequest.Phase.AUTHORIZED) {
            request?.copyText()?.let { screen?.showAuthorized(it) }
        }
    }

    override fun onPause() {
        resumed = false
        if (request?.phase != ClipboardImportRequest.Phase.AUTHENTICATING) discardAndFinish()
        super.onPause()
    }

    override fun onStop() {
        screen?.clearText()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second external request cannot replace text bound to authentication.
        discardAndFinish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun onDestroy() {
        discard()
        super.onDestroy()
    }

    private fun performAction() {
        if (!resumed || !hasWindowFocus() || isFinishing) return
        val current = request ?: return
        when (current.phase) {
            ClipboardImportRequest.Phase.REVIEW -> authenticate(current)
            ClipboardImportRequest.Phase.AUTHORIZED -> save(current)
            else -> Unit
        }
    }

    private fun importAiContext() {
        if (!resumed || !hasWindowFocus() || isFinishing ||
            !graph.settings.learningEnabled || graph.settings.incognitoMode) return
        val current = request ?: return
        val text = current.copyForAi() ?: return
        graph.aiContentInbox.put(
            dev.zeroinput.ime.ai.AiImportedContent(text.toCharArray(), emptyList())
        )
        Toast.makeText(this, R.string.ai_import_ready, Toast.LENGTH_LONG).show()
        discardAndFinish()
    }

    private fun authenticate(current: ClipboardImportRequest) {
        if (!current.beginAuthentication()) return
        screen?.showBusy(authenticating = true)
        val enable = !graph.settings.secureClipboardEnabled
        authentication = AuthenticationBroker.requestCancellable(this) { result ->
            authentication = null
            if (request !== current || isFinishing || isDestroyed) return@requestCancellable
            if (result == null) {
                showMessage(R.string.authentication_failed)
                discardAndFinish()
                return@requestCancellable
            }
            if (!current.authorize()) {
                discardAndFinish()
                return@requestCancellable
            }
            if (enable) {
                enabling = true
                graph.settings.secureClipboardEnabled = true
                enabling = false
            }
            grant = result
            mainHandler.removeCallbacks(expire)
            mainHandler.postDelayed(expire, AUTHORIZED_TIMEOUT_MILLIS)
            if (resumed) current.copyText()?.let { screen?.showAuthorized(it) }
        }
    }

    private fun save(current: ClipboardImportRequest) {
        val authorization = grant ?: return
        if (!graph.settings.secureClipboardEnabled || !current.beginSave()) return
        grant = null
        val label = screen?.label().orEmpty()
        screen?.showBusy(authenticating = false)
        val vault = graph.secureClipboard
        val generation = vaultGeneration
        val owner = WeakReference(this)
        val handler = mainHandler
        try {
            worker.execute {
                val success = try {
                    val text = current.copyText()
                    if (text == null) false else {
                        vault.add(label, text, authorization, generation, current::isSaving)
                        true
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    current.close()
                }
                handler.post { owner.get()?.completeSave(current, success) }
            }
        } catch (_: RejectedExecutionException) {
            completeSave(current, false)
        }
    }

    private fun completeSave(current: ClipboardImportRequest, success: Boolean) {
        if (request !== current || isFinishing || isDestroyed) return
        if (resumed) showMessage(if (success) R.string.clipboard_import_saved else R.string.operation_failed)
        discardAndFinish()
    }

    private fun discardAndFinish() {
        discard()
        finish()
    }

    private fun discard() {
        request?.close()
        request = null
        grant = null
        screen?.clearText()
        screen?.onAction = {}
        screen?.onAiAction = {}
        screen?.onCancel = {}
        authentication?.close()
        authentication = null
        settingsListener?.close()
        settingsListener = null
        mainHandler.removeCallbacks(expire)
        worker.shutdownNow()
    }

    private fun showMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val DRAFT_TIMEOUT_MILLIS = 120_000L
        const val AUTHORIZED_TIMEOUT_MILLIS = 30_000L
    }
}
