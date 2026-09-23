package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.*
import org.junit.Test

class WordAssociationControllerTest {
    @Test fun `only accepted commits offer next words and each accepted selection writes back once`() {
        val f = Fixture()
        f.commit("hello")
        assertFalse(f.controller.state.snapshot.isComposing)
        val first = f.controller.state.snapshot.candidates.single()
        assertEquals(CandidateKind.NEXT_WORD, first.kind)
        val learns = f.learning
        f.controller.handle(InputCommand.SelectCandidate(0, first.id))
        assertEquals(listOf("hello", " world"), f.editor.commits)
        assertEquals(learns + 1, f.learning)
        assertEquals(2, f.queries)
        assertNotEquals(first.id, f.controller.state.snapshot.candidates.single().id)
        f.controller.handle(InputCommand.SelectCandidate(0, first.id))
        assertEquals(2, f.editor.commits.size)
        assertEquals(learns + 1, f.learning)
    }

    @Test fun `failed commit never reaches the predictor`() {
        val f = Fixture()
        f.editor.accept = false
        f.commit("hello")
        assertEquals(0, f.queries)
        assertTrue(f.controller.state.snapshot.candidates.isEmpty())
    }

    @Test fun `failed next word commit clears candidates without querying or learning`() {
        val f = Fixture()
        f.commit("hello")
        val old = f.controller.state.snapshot.candidates.single()
        val learns = f.learning
        f.editor.accept = false
        f.controller.handle(InputCommand.SelectCandidate(0, old.id))
        assertEquals(listOf("hello"), f.editor.commits)
        assertEquals(1, f.queries)
        assertEquals(learns, f.learning)
        assertTrue(f.controller.state.snapshot.candidates.isEmpty())
    }

    @Test fun `space and enter never accept a next word even after paging`() {
        for (key in listOf(InputCommand.Space, InputCommand.Enter)) {
            val f = Fixture()
            f.commit("hello")
            f.controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
            f.controller.handle(key)
            assertFalse(f.editor.commits.contains(" world"))
            if (key == InputCommand.Space) assertEquals(" ", f.editor.commits.last())
            else assertEquals(1, f.editor.enters)
            assertTrue(f.controller.state.snapshot.candidates.isEmpty())
        }
    }

    @Test fun `typing hides predictions and a delayed old candidate cannot select composition`() {
        val f = Fixture()
        f.commit("hello")
        val old = f.controller.state.snapshot.candidates.single()
        f.controller.handle(InputCommand.Text("new"))
        assertTrue(f.controller.state.snapshot.isComposing)
        assertTrue(f.controller.state.snapshot.candidates.none { it.kind == CandidateKind.NEXT_WORD })
        f.controller.handle(InputCommand.SelectCandidate(0, old.id))
        assertEquals(listOf("hello"), f.editor.commits)
    }

    @Test fun `deletion literals and explicit invalidation discard context and old routes`() {
        for (action in listOf<(Fixture) -> Unit>(
            { it.controller.handle(InputCommand.Backspace) },
            { it.controller.handle(InputCommand.LiteralText("!")) },
            { it.controller.invalidateWordAssociations() },
            { it.controller.reset() },
            { it.controller.close() },
            { it.controller.switchLanguage() },
        )) {
            val f = Fixture()
            f.commit("hello")
            val old = f.controller.state.snapshot.candidates.single()
            action(f)
            f.controller.refreshPersonalization()
            assertTrue(f.controller.state.snapshot.candidates.isEmpty())
            f.controller.handle(InputCommand.SelectCandidate(0, old.id))
            assertFalse(f.editor.commits.contains(" world"))
        }
    }

    @Test fun `disabled setting takes effect at selection without waiting for a UI callback`() {
        val f = Fixture()
        f.commit("hello")
        val old = f.controller.state.snapshot.candidates.single()
        f.enabled = false
        f.controller.handle(InputCommand.SelectCandidate(0, old.id))
        assertEquals(listOf("hello"), f.editor.commits)
        assertTrue(f.controller.state.snapshot.candidates.isEmpty())
        f.enabled = true
        f.controller.refreshPersonalization()
        assertTrue(f.controller.state.snapshot.candidates.isEmpty())
    }

    @Test fun `privacy tightening clears existing predictions and blocks future lookup`() {
        for (privacy in listOf(PrivacyConfiguration(incognitoMode = true), PrivacyConfiguration(learningEnabled = false))) {
            val f = Fixture()
            f.commit("hello")
            f.controller.updatePrivacy(privacy)
            f.commit("hello")
            assertEquals(1, f.queries)
            assertTrue(f.controller.state.snapshot.candidates.isEmpty())
        }
    }

    @Test fun `sensitive identifier unknown numeric and no learning editors never query`() {
        val types = listOf(0, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        for (type in types) {
            val f = Fixture(editorInfo = editor(type))
            f.commit("hello")
            assertEquals(0, f.queries)
        }
        val f = Fixture(editorInfo = editor().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING })
        f.commit("hello")
        assertEquals(0, f.queries)
    }

    @Test fun `lookup failure does not repeat a successful editor commit or break input`() {
        val f = Fixture(failLookup = true)
        f.commit("hello")
        assertEquals(listOf("hello"), f.editor.commits)
        assertTrue(f.controller.state.snapshot.candidates.isEmpty())
        f.commit("again")
        assertEquals(listOf("hello", "again"), f.editor.commits)
    }

    @Test fun `unconsumed literal key breaks context before the next successful word`() {
        val f = Fixture(declinePunctuation = true)
        f.commit("hello")
        f.controller.handle(InputCommand.Text("!"))
        f.commit("again")
        assertEquals(listOf("hello", "again"), f.contexts)
        assertEquals(listOf("hello", "!", "again"), f.editor.commits)
    }

    private class Fixture(editorInfo: EditorInfo = editor(), failLookup: Boolean = false, declinePunctuation: Boolean = false) {
        val editor = RecordingEditor()
        var enabled = true
        var learning = 0
        var queries = 0
        val contexts = mutableListOf<String>()
        val controller = InputSessionController(editor, { MemoryEngine(declinePunctuation) }, object : PersonalizationStore {
            override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
            override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) { learning++ }
            override fun recordUse(id: String, learningAllowed: Boolean) = Unit
        }, nextWordPredictor = NextWordPredictor { _, context, _ ->
            queries++
            contexts += context.toString()
            if (failLookup) error("Fixture failure")
            listOf(NextWordSuggestion("world", " world"))
        }, wordAssociationsEnabled = { enabled }).apply {
            start(editorInfo, InputLanguage.ENGLISH, PrivacyConfiguration())
        }
        fun commit(text: String) {
            controller.handle(InputCommand.Text(text))
            controller.handle(InputCommand.SelectCandidate(0))
        }
    }

    private class RecordingEditor : EditorConnection {
        val commits = mutableListOf<String>()
        var accept = true
        var enters = 0
        override fun commitText(text: String): Boolean { if (accept) commits += text; return accept }
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int): Boolean { enters++; return true }
        override fun sendEnterKey() { enters++ }
    }

    private class MemoryEngine(private val declinePunctuation: Boolean = false) : InputEngine {
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", InputLanguage.entries.toSet())
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() { reset() }
        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> if (declinePunctuation && key.text == "!") EngineUpdate(reset(), consumed = false) else {
                snapshot = EngineSnapshot(key.text, key.text, listOf(Candidate("typed", key.text)))
                EngineUpdate(snapshot)
            }
            EngineKey.Space -> EngineUpdate(reset(), " ")
            else -> EngineUpdate(reset(), consumed = false)
        }
        override fun selectCandidate(index: Int): EngineUpdate {
            val text = snapshot.candidates.getOrNull(index)?.text.orEmpty()
            return EngineUpdate(reset(), text)
        }
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
    }

    private companion object {
        fun editor(type: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
            inputType = type
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
    }
}
