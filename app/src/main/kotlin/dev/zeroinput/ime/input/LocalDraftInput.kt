package dev.zeroinput.ime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.AppGraph
import dev.zeroinput.ime.EngineWarmupCoordinator
import dev.zeroinput.ime.EngineWarmupRequest
import dev.zeroinput.ime.EngineWarmupResult
import dev.zeroinput.ime.EngineWarmupResultDelivery
import dev.zeroinput.ime.core.EditorConnection
import dev.zeroinput.ime.core.InputCommand
import dev.zeroinput.ime.core.InputSessionController
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.ui.KeyboardAction

/** A separate conversion session whose only commit target is a bounded memory draft. */
internal class LocalDraftInput(
    private val graph: () -> AppGraph,
    private val post: (Runnable) -> Boolean,
    private val removePosted: (Runnable) -> Unit,
    private val render: (committed: String, preedit: String, state: InputSessionState) -> Unit,
    private val maxChars: Int,
    private val multiline: Boolean = true,
) : AutoCloseable {
    private val text = StringBuilder()
    private var controller: InputSessionController? = null
    private var warmup: EngineWarmupCoordinator? = null
    private var delivery: EngineWarmupResultDelivery? = null
    private var runtimeObserver: AutoCloseable? = null
    private var generation = 0L
    private var retryWhenIdle = false
    private var idleRetries = 0
    var chineseOptions: ChineseInputOptions = ChineseInputOptions()
        private set

    init { require(maxChars in 1..65_536) }

    fun start(language: InputLanguage, options: ChineseInputOptions = ChineseInputOptions()) {
        close()
        chineseOptions = options
        val session = ++generation
        val owner = graph()
        val input = InputSessionController(
            connection = DraftConnection(), engineProvider = { owner.createDraftEngine(it, chineseOptions) },
            personalization = NoPersonalization,
            deferHeavyEngineCreation = true,
            onStateChanged = ::emit,
        )
        controller = input
        input.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, language,
            PrivacyConfiguration(learningEnabled = false))
        val handoff = EngineWarmupResultDelivery(post, removePosted = removePosted) { result ->
            if (session == generation) handleWarmupResult(input, result)
        }
        delivery = handoff
        warmup = EngineWarmupCoordinator(owner.engineExecutor, owner::prepareEngine, handoff::offer)
        runtimeObserver = owner.rime.runtime.addStateListener { state ->
            if (state == dev.zeroinput.engine.rime.RimeRuntimeState.READY) post(Runnable {
                if (session == generation && controller?.state?.language == InputLanguage.CHINESE) prepare()
            })
        }
        prepare()
    }

    fun handle(action: KeyboardAction) {
        val input = controller ?: return
        when (action) {
            is KeyboardAction.Text -> input.handle(InputCommand.Text(action.value))
            is KeyboardAction.LiteralText -> input.handle(InputCommand.LiteralText(action.value))
            is KeyboardAction.PairedText -> input.handle(InputCommand.PairedText(action.opening, action.closing))
            KeyboardAction.Backspace -> input.handle(InputCommand.Backspace)
            KeyboardAction.SelectSyllable -> input.handle(InputCommand.SelectSyllable)
            KeyboardAction.Space -> input.handle(InputCommand.Space)
            KeyboardAction.Enter -> input.handle(InputCommand.Enter)
            KeyboardAction.SwitchLanguage -> { input.switchLanguage(); prepare() }
            else -> Unit
        }
        // Editor fallbacks (notably deleting committed text) run after the
        // controller publishes its engine snapshot. Publish the final draft too.
        emit(input.state)
        resumePreparation()
    }

    fun command(command: InputCommand) {
        controller?.handle(command)
        resumePreparation()
    }

    fun clearComposition(): Boolean {
        val input = controller ?: return false
        val composing = input.state.snapshot.isComposing
        if (composing) input.reset()
        resumePreparation()
        return composing
    }

    fun clear() {
        controller?.reset()
        wipeText()
        controller?.state?.let(::emit)
        resumePreparation()
    }

    fun submittedText(): String {
        val input = controller ?: return ""
        // Some engines select one syllable at a time. Bound conversion by the
        // draft budget and stop if an engine fails to advance its preedit.
        var remaining = maxChars
        while (remaining-- > 0 && input.state.snapshot.isComposing) {
            val before = input.state.snapshot
            if (before.candidates.isEmpty()) input.handle(InputCommand.Enter)
            else input.handle(InputCommand.SelectCandidate(before.highlightedIndex.coerceIn(before.candidates.indices)))
            if (input.state.snapshot == before) {
                input.handle(InputCommand.Enter)
                break
            }
        }
        resumePreparation()
        return text.toString()
    }

    fun committedText(): String = text.toString()

    fun replaceCommittedText(value: String): Boolean {
        val input = controller ?: return false
        if (value.length > maxChars) return false
        input.reset()
        wipeText()
        text.append(value)
        emit(input.state)
        resumePreparation()
        return true
    }

    fun appendImported(value: String): Boolean {
        if (value.length + text.length > maxChars || controller == null) return false
        controller?.reset()
        text.append(value)
        controller?.state?.let(::emit)
        resumePreparation()
        return true
    }

    /**
     * Re-enters an interrupted pre-edit as a composition instead of as text.
     *
     * Opening the AI panel hands over the raw pinyin of a composition that was
     * still open in the editor. Appending that reading as text leaves
     * unreadable letters in the draft; feeding it back as key events lets the
     * draft engine convert it exactly like fresh typing.  Converted text (a
     * selected segment) has no reading left to convert and stays literal.
     */
    fun appendPreedit(value: String): Boolean {
        val input = controller ?: return false
        if (!dev.zeroinput.ime.core.ChinesePreeditCode.accepts(value)) return appendImported(value)
        if (value.length + text.length > maxChars) return false
        val accepted = runCatching {
            value.forEach { input.handle(InputCommand.Text(it.toString())) }
            emit(input.state)
            true
        }.getOrDefault(false)
        resumePreparation()
        return accepted
    }

    /** Committed text only; the unfinished code is a separate pre-edit. */
    private fun emit(state: InputSessionState) {
        render(text.toString(), preeditOf(state), state)
    }

    private fun preeditOf(state: InputSessionState): String =
        // Not EngineSnapshot.composition: Rime formats a multi-syllable
        // pre-edit as "ni hao", which reads as committed words inside the
        // draft. The typed code is what the user is still entering.
        if (state.snapshot.isComposing) state.snapshot.rawInput else ""

    private fun prepare() {
        val state = controller?.state ?: return
        retryWhenIdle = false
        // A retry must also re-attempt native runtime initialization; without
        // it an unavailable engine can never come back during this session.
        warmup?.request(EngineWarmupRequest(generation, state.language, null, null, state.privacy,
            chineseOptions = chineseOptions,
            retryInitialization = idleRetries > 0))
    }

    private fun handleWarmupResult(input: InputSessionController, result: EngineWarmupResult) {
        when (result) {
            // A rejected or failed preparation used to be dropped, leaving the
            // draft on the small built-in table for the rest of the session.
            is EngineWarmupResult.Unavailable -> schedulePrepareRetry()
            is EngineWarmupResult.Prepared -> {
                // The controller can restore plain fallback pinyin without
                // committing it. An idle-only delivery gate discards the
                // prepared engine forever when the user starts typing before
                // the worker finishes.
                if (input.adoptPreparedEngine(result.engine)) idleRetries = 0
                else if (input.state.engineDescriptor?.isFallback == true) schedulePrepareRetry()
            }
        }
    }

    /** Keeps a runtime that stays unavailable from retrying on every idle key. */
    private fun schedulePrepareRetry() {
        if (idleRetries >= MAX_PREPARE_RETRIES) return
        idleRetries++
        retryWhenIdle = true
    }

    private fun resumePreparation() {
        delivery?.resume()
        if (retryWhenIdle && controller?.state?.snapshot?.isComposing == false) prepare()
    }

    override fun close() {
        generation++
        retryWhenIdle = false
        idleRetries = 0
        runtimeObserver?.close()
        runtimeObserver = null
        warmup?.close()
        warmup = null
        delivery?.close()
        delivery = null
        controller?.close()
        controller = null
        wipeText()
    }

    private inner class DraftConnection : EditorConnection {
        // The draft has no editor-owned composing span; the pre-edit is
        // published from the engine snapshot instead.
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun commitText(text: String): Boolean {
            if (this@LocalDraftInput.text.length + text.length > maxChars) return false
            this@LocalDraftInput.text.append(text)
            return true
        }
        override fun commitPairedText(text: String, cursorOffset: Int): Boolean = commitText(text)
        override fun deleteBeforeCursor() {
            if (text.isNotEmpty()) {
                val end = text.offsetByCodePoints(text.length, -1)
                for (index in end until text.length) text.setCharAt(index, '\u0000')
                text.setLength(end)
            }
        }
        override fun performEditorAction(actionId: Int): Boolean = false
        override fun sendEnterKey() { if (multiline) commitText("\n") }
    }

    private fun wipeText() {
        for (index in 0 until text.length) text.setCharAt(index, '\u0000')
        text.setLength(0)
    }

    private companion object {
        /** Recovery attempts per session before the draft accepts the fallback. */
        const val MAX_PREPARE_RETRIES = 3
    }

    private object NoPersonalization : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
