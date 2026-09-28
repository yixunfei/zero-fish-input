package dev.zeroinput.ime.ai

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.ai.api.AiLimits
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
internal class AiDraftInput(
    private val graph: () -> AppGraph,
    private val post: (Runnable) -> Boolean,
    private val render: (String, InputSessionState) -> Unit,
) : AutoCloseable {
    private val text = StringBuilder()
    private var composition = ""
    private var controller: InputSessionController? = null
    private var warmup: EngineWarmupCoordinator? = null
    private var delivery: EngineWarmupResultDelivery? = null
    private var generation = 0L

    fun start(language: InputLanguage) {
        close()
        val session = ++generation
        val owner = graph()
        val input = InputSessionController(
            connection = DraftConnection(), engineProvider = owner::createImmediateEngine,
            personalization = NoPersonalization,
            deferHeavyEngineCreation = true,
            onStateChanged = { render(text.toString() + composition, it) },
        )
        controller = input
        input.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, language,
            PrivacyConfiguration(learningEnabled = false))
        val handoff = EngineWarmupResultDelivery(post) { result ->
            if (session == generation && result is EngineWarmupResult.Prepared) {
                input.adoptPreparedEngine(result.engine)
            }
        }
        delivery = handoff
        warmup = EngineWarmupCoordinator(owner.engineExecutor, owner::prepareEngine, handoff::offer)
        prepare()
    }

    fun handle(action: KeyboardAction) {
        val input = controller ?: return
        when (action) {
            is KeyboardAction.Text -> input.handle(InputCommand.Text(action.value))
            is KeyboardAction.LiteralText -> input.handle(InputCommand.LiteralText(action.value))
            KeyboardAction.Backspace -> input.handle(InputCommand.Backspace)
            KeyboardAction.Space -> input.handle(InputCommand.Space)
            KeyboardAction.Enter -> input.handle(InputCommand.Enter)
            KeyboardAction.SwitchLanguage -> { input.switchLanguage(); prepare() }
            else -> Unit
        }
    }

    fun command(command: InputCommand) { controller?.handle(command) }

    fun clearComposition(): Boolean {
        val input = controller ?: return false
        val composing = input.state.snapshot.isComposing
        if (composing) input.reset()
        return composing
    }

    fun clear() {
        controller?.reset()
        text.setLength(0)
        composition = ""
        controller?.state?.let { render("", it) }
    }

    fun submittedText(): String {
        if (controller?.state?.snapshot?.isComposing == true) controller?.handle(InputCommand.Enter)
        return text.toString()
    }

    private fun prepare() {
        val state = controller?.state ?: return
        warmup?.request(EngineWarmupRequest(generation, state.language, null, null, state.privacy))
    }

    override fun close() {
        generation++
        warmup?.close()
        warmup = null
        delivery?.close()
        delivery = null
        controller?.close()
        controller = null
        text.setLength(0)
        composition = ""
    }

    private inner class DraftConnection : EditorConnection {
        override fun setComposingText(text: String) { composition = text.take(AiLimits.MAX_INPUT_CHARS - this@AiDraftInput.text.length) }
        override fun finishComposingText() { composition = "" }
        override fun commitText(text: String): Boolean {
            composition = ""
            if (this@AiDraftInput.text.length + text.length > AiLimits.MAX_INPUT_CHARS) return false
            this@AiDraftInput.text.append(text)
            return true
        }
        override fun deleteBeforeCursor() {
            if (text.isNotEmpty()) text.setLength(text.offsetByCodePoints(text.length, -1))
        }
        override fun performEditorAction(actionId: Int): Boolean = false
        override fun sendEnterKey() { commitText("\n") }
    }

    private object NoPersonalization : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
