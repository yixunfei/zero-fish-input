package dev.zeroinput.ime.input

import android.os.Handler
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.AppGraph
import dev.zeroinput.ime.R
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.core.InputCommand
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.PrivateDraftAction
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.userdata.SecureClipboardVault
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

internal fun isPrivateDraftDeviceAvailable(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

internal fun isPrivateDraftEditor(info: EditorInfo, privacy: SessionPrivacy, incognito: Boolean): Boolean {
    val type = info.inputType
    val ordinaryPolicy = privacy.reason == PrivacyReason.NONE || privacy.reason == PrivacyReason.USER_DISABLED
    val onlyNoSuggestions = privacy.reason == PrivacyReason.EDITOR_REQUEST &&
        type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
    if (incognito || privacy.isSensitive || type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT ||
        type and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0 ||
        info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 ||
        !ordinaryPolicy && !onlyNoSuggestions) return false
    return when (type and InputType.TYPE_MASK_VARIATION) {
        InputType.TYPE_TEXT_VARIATION_NORMAL,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> true
        else -> false
    }
}

internal class PrivateDraftInput(
    private val graph: () -> AppGraph,
    private val main: Handler,
    private val canUse: (ZeroInputView) -> Boolean,
    private val commitTarget: (ZeroInputView) -> ((String) -> Boolean)?,
    private val onSaved: () -> Unit,
    private val notify: (Int) -> Unit,
) : AutoCloseable {
    private val worker = BoundedExecutors.singleThread("zeroinput-private-draft", 1)
    private var view: ZeroInputView? = null
    @Volatile private var pending: PendingSave? = null
    private var pendingCommit: ((String) -> Boolean)? = null
    private val revision = AtomicLong()
    private var task: Future<*>? = null
    private val draft = LocalDraftInput(
        graph, { main.post(it) }, main::removeCallbacks,
        render = ::render,
        maxChars = SecureClipboardVault.MAX_VALUE_LENGTH,
        maskCommitted = true,
        onLengthLimit = { notify(R.string.private_draft_length_limit) },
    )

    private fun render(text: String, preedit: String, state: dev.zeroinput.ime.core.InputSessionState) {
        view?.renderPrivateDraft(text, text.isNotEmpty() || preedit.isNotEmpty(), state,
            busy = pending != null, showFull = draft.showFullPrivateDraft)
    }

    fun open(owner: ZeroInputView, language: InputLanguage, options: ChineseInputOptions) {
        hide()
        view = owner
        draft.start(language, options.copy(keyboardLayout = ChineseKeyboardLayout.FULL,
            doublePinyinScheme = DoublePinyinScheme.OFF))
    }

    fun hide() {
        invalidate()
        pending?.close()
        pending = null
        pendingCommit = null
        task?.cancel(false)
        task = null
        BoundedExecutors.purge(worker)
        view = null
        draft.close()
    }

    fun invalidate() { revision.incrementAndGet() }

    fun type(action: KeyboardAction) {
        if (editable()) draft.handle(action)
    }

    fun command(command: InputCommand) {
        if (editable()) draft.command(command)
    }

    fun clearComposition(): Boolean = editable() && draft.clearComposition()

    fun toggleVisibility() {
        if (editable()) draft.togglePrivateDraftVisibility()
    }

    private fun editable(): Boolean = pending == null && view?.let(canUse) == true

    fun finish(action: PrivateDraftAction) {
        val owner = view ?: return
        if (!editable()) return
        val commit = if (action == PrivateDraftAction.SAVE_AND_CLOSE) null else commitTarget(owner) ?: return
        val saveCopy = when (action) {
            PrivateDraftAction.SAVE_AND_CLOSE -> true
            PrivateDraftAction.SAVE_AND_SUBMIT -> graph().settings.savePrivateDraftOnSubmit
            PrivateDraftAction.SUBMIT_ONLY -> false
        }
        val chars = draft.submittedChars() ?: return
        if (chars.none { !it.isWhitespace() }) {
            chars.fill('\u0000')
            return
        }
        if (!saveCopy) {
            chars.fill('\u0000')
            if (commit?.invoke(draft.committedText()) == true) owner.returnToKeyboard()
            else notify(R.string.private_draft_commit_failed)
            return
        }
        enqueue(graph().secureClipboard, PendingSave(chars), commit)
    }

    private fun enqueue(vault: SecureClipboardVault, request: PendingSave,
                        commit: ((String) -> Boolean)?) {
        val generation = vault.captureGeneration()
        val expectedRevision = revision.get()
        pending = request
        pendingCommit = commit
        draft.refreshDisplay()
        try {
            task = worker.submit {
                val chars = request.take() ?: return@submit
                val saved = try {
                    runCatching {
                        vault.addPrivateDraft(chars, generation) { pending === request && revision.get() == expectedRevision }
                    }.isSuccess
                } finally {
                    chars.fill('\u0000')
                }
                main.post { finishSave(request, expectedRevision, generation == vault.captureGeneration(), saved) }
            }
        } catch (_: RejectedExecutionException) {
            pending = null
            pendingCommit = null
            request.close()
            draft.refreshDisplay()
            notify(R.string.private_draft_save_failed)
        }
    }

    private fun finishSave(request: PendingSave, expectedRevision: Long, generationMatches: Boolean, saved: Boolean) {
        if (pending !== request || revision.get() != expectedRevision) return
        val commit = pendingCommit
        pending = null
        pendingCommit = null
        task = null
        val owner = view ?: return
        if (!canUse(owner)) return
        if (!generationMatches) {
            owner.closeAuxiliaryInput()
            return
        }
        if (!saved) {
            draft.refreshDisplay()
            notify(R.string.private_draft_save_failed)
            return
        }
        if (commit != null && !commit(draft.committedText())) notify(R.string.private_draft_saved_only)
        owner.returnToKeyboard()
        onSaved()
    }

    override fun close() {
        hide()
        worker.shutdownNow()
    }

    private class PendingSave(private var chars: CharArray?) {
        @Synchronized fun take(): CharArray? = chars.also { chars = null }

        @Synchronized fun close() {
            chars?.fill('\u0000')
            chars = null
        }
    }
}
