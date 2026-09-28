package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PartialCompositionFallbackTest {
    @Test fun externalCursorMoveFinishesPreeditWithoutRewritingOrDeletingEditorText() {
        val editor = Editor()
        val controller = controller(editor)
        controller.finishCompositionForCursorMove()
        assertEquals(1, editor.finishes)
        assertEquals(emptyList<String>(), editor.commits)
        assertEquals(0, editor.deletes)
        assertFalse(controller.state.snapshot.isComposing)
        controller.handle(InputCommand.Backspace)
        assertEquals(1, editor.deletes)
        controller.close()
    }

    @Test fun `declined enter preserves selected segments before editor action`() {
        val editor = Editor()
        val controller = controller(editor)
        controller.handle(InputCommand.Enter)
        assertEquals(listOf("你hso"), editor.commits)
        assertEquals(1, editor.enters)
        assertFalse(controller.state.snapshot.isComposing)
        controller.close()
    }

    @Test fun `literal and declined text preserve selected segments before the suffix`() {
        for (command in listOf(InputCommand.LiteralText("4"), InputCommand.Text("4"))) {
            val editor = Editor()
            val controller = controller(editor)
            controller.handle(command)
            assertEquals(listOf("你hso", "4"), editor.commits)
            assertFalse(controller.state.snapshot.isComposing)
            controller.close()
        }
    }

    @Test fun `engine failure preserves selected segments before replaying the new key`() {
        val editor = Editor()
        val controller = controller(editor, fails = true)
        controller.handle(InputCommand.Text("4"))
        assertEquals(listOf("你hso", "4"), editor.commits)
        assertFalse(controller.state.snapshot.isComposing)
        controller.close()
    }

    private fun controller(editor: Editor, fails: Boolean = false) =
        InputSessionController(editor, { PartialEngine(fails) }, Store).apply {
            start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
                InputLanguage.CHINESE, PrivacyConfiguration())
        }

    private class PartialEngine(private val fails: Boolean) : InputEngine {
        override val descriptor = EngineDescriptor("partial", "partial", "1", setOf(InputLanguage.CHINESE))
        override var snapshot = EngineSnapshot("nihso", "你hso", canUndoSelection = true)
        override fun start(context: EditorContext) = snapshot
        override fun handle(key: EngineKey): EngineUpdate {
            check(!fails) { "Fixture engine unavailable" }
            return EngineUpdate(snapshot, consumed = false)
        }
        override fun selectCandidate(index: Int) = EngineUpdate(snapshot, consumed = false)
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() { reset() }
    }

    private class Editor : EditorConnection {
        val commits = mutableListOf<String>()
        var enters = 0
        var finishes = 0
        var deletes = 0
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() { finishes++ }
        override fun commitText(text: String): Boolean { commits += text; return true }
        override fun deleteBeforeCursor() { deletes++ }
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() { enters++ }
    }

    private object Store : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
