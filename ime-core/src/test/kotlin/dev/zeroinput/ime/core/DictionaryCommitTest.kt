package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.*
import org.junit.Test

class DictionaryCommitTest {
    @Test fun spaceCommitsReplacementAndNeverTheHiddenNativeDefault() {
        val page = PersonalSuggestionPage(listOf(PersonalSuggestion("edited", "您好", 99)),
            suppressedDictionaryTexts = setOf("你好"), dictionaryEditsApplied = true)
        assertEquals(listOf("您好"), commit(page, InputCommand.Space))
    }
    @Test fun allDeletedCandidatesFallBackToRawInputForSpaceAndPunctuation() {
        val page = PersonalSuggestionPage(suppressedDictionaryTexts = setOf("你好"), dictionaryEditsApplied = true)
        assertEquals(listOf("nihao"), commit(page, InputCommand.Space))
        assertEquals(listOf("nihao", ","), commit(page, InputCommand.Text(",")))
    }
    @Test fun pendingDictionaryEditsKeepCompositionUntilTheQueryResolves() {
        val page = PersonalSuggestionPage(ready = false, dictionaryEditsApplied = true)
        assertTrue(commit(page, InputCommand.Space).isEmpty())
    }

    private fun commit(page: PersonalSuggestionPage, command: InputCommand): List<String> {
        val connection = Connection()
        val store = object : PagedPersonalizationStore {
            override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int) = page
            override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = page.items
            override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
            override fun recordUse(id: String, learningAllowed: Boolean) = Unit
        }
        InputSessionController(connection, { Engine() }, store).use { controller ->
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
            controller.handle(InputCommand.Text("nihao"))
            controller.handle(command)
        }
        return connection.commits
    }

    private class Connection : EditorConnection {
        val commits = mutableListOf<String>()
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun commitText(text: String): Boolean { commits += text; return true }
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }
    private class Engine : InputEngine {
        override val descriptor = EngineDescriptor("fixture", "Fixture", "1", setOf(InputLanguage.CHINESE))
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() = Unit
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot)
        override fun selectCandidate(index: Int): EngineUpdate { reset(); return EngineUpdate(snapshot, "你好") }
        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> if (key.text == ",") EngineUpdate(snapshot, consumed = false) else {
                snapshot = EngineSnapshot(snapshot.rawInput + key.text, snapshot.rawInput + key.text, listOf(Candidate("native", "你好")))
                EngineUpdate(snapshot)
            }
            else -> selectCandidate(0)
        }
    }
}
