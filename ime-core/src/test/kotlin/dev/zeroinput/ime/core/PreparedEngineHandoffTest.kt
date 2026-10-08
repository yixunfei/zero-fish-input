package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CompositionEditingEngine
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedEngineHandoffTest {
    @Test
    fun `prepared native engine continues a fallback pinyin composition`() {
        val connection = ComposingConnection()
        val fallback = HandoffEngine("fallback", fallback = true)
        val controller = controller(connection, fallback)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("n"))

        val native = HandoffEngine("native")
        val prepared = prepared(controller, native)

        assertTrue(controller.adoptPreparedEngine(prepared))
        assertTrue(fallback.closed)
        assertFalse(native.closed)
        assertEquals("n", controller.state.snapshot.rawInput)

        controller.handle(InputCommand.Text("ihao"))
        val candidate = controller.state.snapshot.candidates.single()
        controller.handle(InputCommand.SelectCandidate(0, candidate.id))

        assertEquals(listOf("你好"), connection.commits)
        assertEquals("", connection.composing)
    }

    @Test
    fun `failed composition restoration leaves the fallback composition intact`() {
        val connection = ComposingConnection()
        val fallback = HandoffEngine("fallback", fallback = true)
        val controller = controller(connection, fallback)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("ni"))

        val native = HandoffEngine("native", restoreConsumed = false)

        assertFalse(controller.adoptPreparedEngine(prepared(controller, native)))
        assertTrue(native.closed)
        assertFalse(fallback.closed)
        assertEquals("ni", controller.state.snapshot.rawInput)
        assertEquals("ni", connection.composing)

        controller.handle(InputCommand.Text("hao"))
        assertEquals("nihao", controller.state.snapshot.rawInput)
        assertTrue(connection.commits.isEmpty())
    }

    @Test
    fun `selected fallback segments are not handed to a new engine`() {
        val connection = ComposingConnection()
        val fallback = HandoffEngine("fallback", fallback = true, selected = true)
        val controller = controller(connection, fallback)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("ni"))

        val native = HandoffEngine("native")

        assertFalse(controller.adoptPreparedEngine(prepared(controller, native)))
        assertTrue(native.closed)
        assertFalse(fallback.closed)
    }

    private fun controller(connection: EditorConnection, engine: InputEngine) =
        InputSessionController(connection, { engine }, EmptyPersonalization)

    private fun prepared(controller: InputSessionController, engine: InputEngine) = PreparedInputEngine(
        language = InputLanguage.CHINESE,
        languagePackKey = null,
        packageName = null,
        privacy = controller.state.privacy,
        snapshot = EngineSnapshot.Empty,
        engine = engine,
    )

    private fun textEditor() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    private class HandoffEngine(
        id: String,
        fallback: Boolean = false,
        private val restoreConsumed: Boolean = true,
        private val selected: Boolean = false,
    ) : InputEngine, CompositionEditingEngine {
        private var current = EngineSnapshot.Empty
        var closed = false
            private set

        override val descriptor = EngineDescriptor(id, id, "1", setOf(InputLanguage.CHINESE), isFallback = fallback)
        override val snapshot: EngineSnapshot get() = current

        override fun start(context: EditorContext): EngineSnapshot = reset()

        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> compose(current.rawInput + key.text)
            else -> EngineUpdate(current, consumed = false)
        }

        override fun selectCandidate(index: Int): EngineUpdate =
            if (index == 0 && current.rawInput == "nihao") {
                current = EngineSnapshot.Empty
                EngineUpdate(current, committedText = "你好", committedInput = "nihao")
            } else {
                EngineUpdate(current, consumed = false)
            }

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun restoreComposition(input: String): EngineUpdate {
            if (!restoreConsumed) return EngineUpdate(current, consumed = false)
            return compose(input)
        }

        override fun undoSelection() = EngineUpdate(current, consumed = false)

        override fun selectSyllable() = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            closed = true
            current = EngineSnapshot.Empty
        }

        private fun compose(input: String): EngineUpdate {
            current = EngineSnapshot(
                rawInput = input,
                composition = input,
                candidates = if (input == "nihao") listOf(Candidate("native:0", "你好")) else emptyList(),
                canUndoSelection = selected,
            )
            return EngineUpdate(current)
        }
    }

    private class ComposingConnection : EditorConnection {
        val commits = mutableListOf<String>()
        var composing = ""

        override fun setComposingText(text: String) {
            composing = text
        }

        override fun finishComposingText() {
            composing = ""
        }

        override fun commitText(text: String): Boolean {
            composing = ""
            commits += text
            return true
        }

        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }

    private object EmptyPersonalization : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
