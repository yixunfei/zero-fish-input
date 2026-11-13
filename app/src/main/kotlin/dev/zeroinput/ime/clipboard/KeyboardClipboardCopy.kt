package dev.zeroinput.ime.clipboard

import android.os.Handler
import android.os.SystemClock
import dev.zeroinput.ime.clipboard.ClipboardImportRequest.Phase
import dev.zeroinput.ime.ui.ClipboardCopyUi
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.userdata.SecureClipboardVault
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/** IME-owned selection snapshot. Only the system authentication detour may unbind it. */
internal class KeyboardClipboardCopy(
    private val main: Handler,
    private val worker: ExecutorService,
    private val vault: SecureClipboardVault,
    private val authenticate: ((AuthenticationGrant?) -> Unit) -> AutoCloseable,
    private val render: (ClipboardCopyUi?) -> Unit,
    private val onAuthenticated: () -> Unit,
    private val onAuthenticationFailed: () -> Unit,
    private val onSaved: (success: Boolean, enable: Boolean) -> Unit,
) {
    private var pending: Copy? = null
    private var authentication: AutoCloseable? = null
    private val expire = Runnable { cancel() }
    val awaitingReturn: Boolean get() = pending?.let { it.session == null } == true
    val isAuthenticating: Boolean get() = pending?.draft?.phase == Phase.AUTHENTICATING

    fun offer(draft: ClipboardImportRequest, generation: Long, source: PasteEditorIdentity, session: Long, enabled: Boolean) {
        cancel()
        if (generation != vault.captureGeneration()) {
            draft.close()
            return
        }
        pending = Copy(draft, generation, source, session, enable = !enabled).also { startDeadline(it, 120_000L) }
        render(ClipboardCopyUi.Review(draft.length, !enabled))
    }

    fun act(session: Long, source: PasteEditorIdentity) {
        val copy = current() ?: return
        if (copy.session != session || copy.source != source) { cancel(); return }
        when (copy.draft.phase) {
            Phase.REVIEW -> requestAuthentication(copy)
            Phase.AUTHORIZED -> save(copy)
            else -> Unit
        }
    }

    fun leaveEditor() {
        if (!awaitingReturn) cancel()
    }

    fun editorStarted(source: PasteEditorIdentity?) {
        val copy = current() ?: return
        if (!awaitingReturn || source == copy.source) return
        // The platform credential window can report TYPE_NULL during its transition.
        // It is only allowed to defer cancellation and cannot bind the draft.
        if (source?.packageName !in CREDENTIAL_PACKAGES) cancel()
    }

    fun bindReturnedEditor(session: Long, source: PasteEditorIdentity) {
        val copy = current() ?: return
        if (copy.draft.phase != Phase.AUTHORIZED || copy.session != null || copy.source != source) return
        copy.session = session
        render(ClipboardCopyUi.ConfirmSave)
    }

    fun selectionChanged() {
        if (!awaitingReturn) cancel()
    }

    fun cancel() {
        val copy = pending ?: return
        pending = null
        copy.draft.close()
        copy.grant = null
        authentication?.close()
        authentication = null
        main.removeCallbacks(expire)
        render(null)
    }

    private fun requestAuthentication(copy: Copy) {
        if (!copy.draft.beginAuthentication()) return
        copy.session = null
        startDeadline(copy, 30_000L)
        render(ClipboardCopyUi.Authenticating)
        authentication = authenticate { grant ->
            if (current() !== copy) return@authenticate
            authentication = null
            if (grant == null || !copy.draft.authorize()) {
                cancel()
                onAuthenticationFailed()
                return@authenticate
            }
            copy.grant = grant
            startDeadline(copy, 30_000L)
            onAuthenticated()
        }
    }

    private fun save(copy: Copy) {
        val grant = copy.grant ?: return
        if (!copy.draft.beginSave()) return
        copy.grant = null
        render(ClipboardCopyUi.Saving)
        try {
            worker.execute {
                val chars = copy.draft.copyChars()
                val success = if (chars == null) false else try {
                    vault.add("", chars, grant, copy.generation) {
                        copy.draft.isSaving() && SystemClock.elapsedRealtime() < copy.deadline
                    }
                    true
                } catch (_: Exception) {
                    false
                } finally {
                    chars.fill('\u0000')
                }
                main.post { completeSave(copy, success) }
            }
        } catch (_: RejectedExecutionException) {
            completeSave(copy, false)
        }
    }

    private fun completeSave(copy: Copy, success: Boolean) {
        if (pending !== copy) return
        cancel()
        onSaved(success, success && copy.enable)
    }

    private fun current(): Copy? {
        val copy = pending ?: return null
        if (SystemClock.elapsedRealtime() >= copy.deadline || copy.generation != vault.captureGeneration()) {
            cancel()
            return null
        }
        return copy
    }

    private fun startDeadline(copy: Copy, duration: Long) {
        copy.deadline = SystemClock.elapsedRealtime() + duration
        main.removeCallbacks(expire)
        main.postDelayed(expire, duration)
    }

    private class Copy(
        val draft: ClipboardImportRequest,
        val generation: Long,
        val source: PasteEditorIdentity,
        var session: Long?,
        val enable: Boolean,
    ) {
        var grant: AuthenticationGrant? = null
        var deadline = 0L
    }

    private companion object {
        val CREDENTIAL_PACKAGES = setOf("com.android.systemui", "com.android.settings")
    }
}
