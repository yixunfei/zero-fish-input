package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.*
import org.junit.Test

class CompositionCursorRoutingTest {
    @Test fun validSelectionOnlyUpdatesTheEngineAndStaleOrInvalidRequestsAreRejected() {
        val engine = Engine()
        val editor = Editor()
        val controller = controller(engine, editor)
        controller.handle(InputCommand.SelectComposition(CompositionSelection(1), "chaxun"))
        assertEquals(1, engine.selections)
        assertEquals(CompositionSelection(1), controller.state.snapshot.selection)
        controller.handle(InputCommand.SelectComposition(CompositionSelection(2), "old"))
        controller.handle(InputCommand.SelectComposition(CompositionSelection(99), "chaxun"))
        assertEquals(1, engine.selections)
        assertEquals(0, editor.operations)
        controller.reset()
        controller.handle(InputCommand.SelectComposition(CompositionSelection(1), "chaxun"))
        assertEquals(1, engine.selections)
        controller.close()
    }

    @Test fun passwordSessionNeverRoutesCompositionSelection() {
        val engine = Engine()
        val editor = Editor()
        val controller = controller(engine, editor, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        controller.handle(InputCommand.SelectComposition(CompositionSelection(1), "chaxun"))
        assertEquals(0, engine.selections)
        assertEquals(0, editor.operations)
        controller.close()
    }

    private fun controller(engine: Engine, editor: Editor, type: Int = InputType.TYPE_CLASS_TEXT) =
        InputSessionController(editor, { engine }, Store).apply {
            start(EditorInfo().apply { inputType = type }, InputLanguage.CHINESE, PrivacyConfiguration())
            editor.operations = 0
        }

    private class Engine : InputEngine, CompositionCursorEngine {
        var selections = 0
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE))
        override var snapshot = EngineSnapshot("chaxun", "chaxun", selection = CompositionSelection(6))
        override fun start(context: EditorContext) = snapshot
        override fun selectComposition(selection: CompositionSelection): EngineUpdate {
            selections++
            snapshot = snapshot.copy(selection = selection)
            return EngineUpdate(snapshot)
        }
        override fun handle(key: EngineKey) = EngineUpdate(snapshot)
        override fun selectCandidate(index: Int) = EngineUpdate(snapshot)
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() { reset() }
    }

    private class Editor : EditorConnection {
        var operations = 0
        override fun setComposingText(text: String) { operations++ }
        override fun finishComposingText() { operations++ }
        override fun commitText(text: String): Boolean { operations++; return true }
        override fun deleteBeforeCursor() { operations++ }
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() { operations++ }
    }

    private object Store : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
