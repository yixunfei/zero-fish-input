package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.*
import org.junit.Test

class ReconversionControllerTest {
    @Test fun `canonical reading is used for completed learning and failed reopening does not mutate the editor`() {
        val engine = Engine()
        val editor = Editor()
        val store = Store()
        val controller = InputSessionController(editor, { engine }, store)
        controller.start(info(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("nihso"))
        controller.handle(InputCommand.SelectCandidate(0))
        assertEquals("nihao", store.reading)
        assertTrue(controller.state.canReconvert)
        editor.allow = false
        controller.handle(InputCommand.ReconvertLast)
        assertFalse(controller.state.canReconvert)
        assertFalse(controller.state.snapshot.isComposing)
        assertEquals(listOf("你好"), editor.commits)
        controller.handle(InputCommand.ReconvertLast)
        assertEquals(1, editor.reopens)
    }

    @Test fun `successful reconversion keeps the restored composition visible`() {
        val engine = Engine()
        val editor = Editor()
        val controller = InputSessionController(editor, { engine }, Store())
        controller.start(info(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("nihao"))
        controller.handle(InputCommand.SelectCandidate(0))

        controller.handle(InputCommand.ReconvertLast)

        assertTrue(controller.state.snapshot.isComposing)
        assertEquals("nihao", controller.state.snapshot.rawInput)
        assertEquals("你好", controller.state.snapshot.candidates.single().text)
    }

    @Test fun `session changes privacy tightening reset and continued typing revoke the recent word`() {
        for (action in 0..4) {
            val engine = Engine()
            val editor = Editor()
            val controller = InputSessionController(editor, { engine }, Store())
            controller.start(info(), InputLanguage.CHINESE, PrivacyConfiguration())
            controller.handle(InputCommand.Text("nihao"))
            controller.handle(InputCommand.SelectCandidate(0))
            assertTrue(controller.state.canReconvert)
            when (action) {
                0 -> controller.start(info(), InputLanguage.CHINESE, PrivacyConfiguration())
                1 -> controller.updatePrivacy(PrivacyConfiguration(incognitoMode = true))
                2 -> controller.reset()
                3 -> controller.handle(InputCommand.Text("a"))
                4 -> controller.close()
            }
            assertFalse(controller.state.canReconvert)
            controller.handle(InputCommand.ReconvertLast)
            assertEquals(0, editor.reopens)
            controller.close()
        }
    }

    @Test fun `unverified corrected reading cannot create a learned phrase`() {
        val engine = Engine().apply { learnable = false }
        val store = Store()
        val controller = InputSessionController(Editor(), { engine }, store)
        controller.start(info(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("nihso"))
        controller.handle(InputCommand.SelectCandidate(0))
        assertEquals("", store.reading)
    }

    private fun info() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
    private class Engine : InputEngine, CompositionEditingEngine {
        var learnable = true
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE))
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> restoreComposition(key.text)
            else -> EngineUpdate(snapshot, consumed = false)
        }
        override fun selectCandidate(index: Int) = EngineUpdate(reset(), "你好", committedInput = "nihao", learnable = learnable)
        override fun restoreComposition(input: String): EngineUpdate {
            snapshot = EngineSnapshot(input, input, listOf(Candidate("fixture", "你好")))
            return EngineUpdate(snapshot)
        }
        override fun undoSelection() = EngineUpdate(snapshot, consumed = false)
        override fun selectSyllable() = EngineUpdate(snapshot, consumed = false)
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() { reset() }
    }

    private class Editor : EditorConnection, ReconversionEditorConnection {
        var allow = true
        var reopens = 0
        val commits = ArrayList<String>()
        override fun reopenCommittedText(expectedText: String): Boolean { reopens++; return allow }
        override fun invalidateReconversion() = Unit
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun commitText(text: String): Boolean { commits += text; return true }
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }
    private class Store : PersonalizationStore {
        var reading = ""
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) { reading = shortcut }
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}
