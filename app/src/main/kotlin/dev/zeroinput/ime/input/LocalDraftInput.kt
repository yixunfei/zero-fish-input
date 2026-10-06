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
    private val render: (String, InputSessionState) -> Unit,
    private val maxChars: Int,
    private val multiline: Boolean = true,
) : AutoCloseable {
    private val text = StringBuilder()
    private var composition = ""
    private var controller: InputSessionController? = null
    private var warmup: EngineWarmupCoordinator? = null
    private var delivery: EngineWarmupResultDelivery? = null
    private var runtimeObserver: AutoCloseable? = null
    private var generation = 0L

    init { require(maxChars in 1..65_536) }

    fun start(language: InputLanguage) {
        close()
        val session = ++generation
        val owner = graph()
        val input = InputSessionController(
            connection = DraftConnection(), engineProvider = owner::createDraftEngine,
            personalization = NoPersonalization,
            deferHeavyEngineCreation = true,
            onStateChanged = { render(text.toString() + composition, it) },
        )
        controller = input
        input.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, language,
            PrivacyConfiguration(learningEnabled = false))
        val handoff = EngineWarmupResultDelivery(post, ready = { !input.state.snapshot.isComposing }) { result ->
            if (session == generation && result is EngineWarmupResult.Prepared) {
                input.adoptPreparedEngine(result.engine)
            }
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
            KeyboardAction.Space -> input.handle(InputCommand.Space)
            KeyboardAction.Enter -> input.handle(InputCommand.Enter)
            KeyboardAction.SwitchLanguage -> { input.switchLanguage(); prepare() }
            else -> Unit
        }
        // Editor fallbacks (notably deleting committed text) run after the
        // controller publishes its engine snapshot. Publish the final draft too.
        render(text.toString() + composition, input.state)
        delivery?.resume()
    }

    fun command(command: InputCommand) {
        controller?.handle(command)
        delivery?.resume()
    }

    fun clearComposition(): Boolean {
        val input = controller ?: return false
        val composing = input.state.snapshot.isComposing
        if (composing) input.reset()
        delivery?.resume()
        return composing
    }

    fun clear() {
        controller?.reset()
        wipeText()
        composition = ""
        controller?.state?.let { render("", it) }
        delivery?.resume()
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
        delivery?.resume()
        return text.toString()
    }

    fun appendImported(value: String): Boolean {
        if (value.length + text.length > maxChars || controller == null) return false
        controller?.reset()
        text.append(value)
        controller?.state?.let { render(text.toString(), it) }
        delivery?.resume()
        return true
    }

    private fun prepare() {
        val state = controller?.state ?: return
        warmup?.request(EngineWarmupRequest(generation, state.language, null, null, state.privacy))
    }

    override fun close() {
        generation++
        runtimeObserver?.close()
        runtimeObserver = null
        warmup?.close()
        warmup = null
        delivery?.close()
        delivery = null
        controller?.close()
        controller = null
        wipeText()
        composition = ""
    }

    private inner class DraftConnection : EditorConnection {
        override fun setComposingText(text: String) { composition = text.take(maxChars - this@LocalDraftInput.text.length) }
        override fun finishComposingText() { composition = "" }
        override fun commitText(text: String): Boolean {
            composition = ""
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

    private object NoPersonalization : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
