package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class CandidateWindowTest {
    @Test fun `first append preserves existing candidate identities and selection routes`() {
        val engine = Pages()
        val window = CandidateWindow()
        val first = window.snapshot(engine.snapshot).candidates
        val update = window.changePage(engine, engine.snapshot, PageDirection.NEXT)
        val result = window.snapshot(update.snapshot)
        assertEquals(first, result.candidates.take(first.size))
        val route = checkNotNull(window.route(first.first().id))
        assertEquals("entry0", window.select(engine, route, update.snapshot).committedText)
    }

    @Test fun `space selects the displayed highlight after browsing instead of the last native page`() {
        val engine = Pages()
        var committed = ""
        val editor = object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String): Boolean { committed = text; return true }
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        val store = object : PersonalizationStore {
            override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
            override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
            override fun recordUse(id: String, learningAllowed: Boolean) = Unit
        }
        val controller = InputSessionController(editor, { engine }, store)
        controller.start(android.view.inputmethod.EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT },
            InputLanguage.CHINESE, dev.zeroinput.ime.core.privacy.PrivacyConfiguration())
        controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
        val highlighted = controller.state.snapshot.candidates[controller.state.snapshot.highlightedIndex].text
        controller.handle(InputCommand.Space)
        assertEquals(highlighted, committed)
        assertEquals("entry0", committed)
        controller.close()
    }

    @Test fun `enter after browsing commits raw input and removes cached candidates`() {
        val engine = Pages()
        val commits = mutableListOf<String>()
        val editor = object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String): Boolean { commits += text; return true }
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        val store = object : PersonalizationStore {
            override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
            override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
            override fun recordUse(id: String, learningAllowed: Boolean) = Unit
        }
        val controller = InputSessionController(editor, { engine }, store)
        controller.start(android.view.inputmethod.EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT },
            InputLanguage.CHINESE, dev.zeroinput.ime.core.privacy.PrivacyConfiguration())
        controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
        assertTrue(controller.state.snapshot.candidates.isNotEmpty())

        controller.handle(InputCommand.Enter)

        assertEquals(listOf("ni"), commits)
        assertFalse(controller.state.snapshot.isComposing)
        assertTrue(controller.state.snapshot.candidates.isEmpty())
        controller.close()
    }

    @Test fun `all pages remain reachable while only six pages stay resident`() {
        val engine = Pages()
        val window = CandidateWindow()
        val seen = mutableSetOf<String>()
        var current = engine.snapshot
        seen += window.snapshot(current).candidates.map { it.text }
        repeat(19) {
            current = window.changePage(engine, current, PageDirection.NEXT).snapshot
            val visible = window.snapshot(current)
            assertTrue(visible.candidates.size <= CandidateWindow.MAX_PAGES * 4)
            seen += visible.candidates.map { it.text }
        }
        assertEquals(80, seen.size)
        assertFalse(window.snapshot(current).hasNextPage)
        repeat(14) { current = window.changePage(engine, current, PageDirection.PREVIOUS).snapshot }
        assertFalse(window.snapshot(current).hasPreviousPage)
        assertEquals("entry0", window.snapshot(current).candidates.first().text)
    }

    @Test fun `selecting an earlier visible page verifies and commits its original entry`() {
        val engine = Pages()
        val window = CandidateWindow()
        var current = engine.snapshot
        repeat(4) { current = window.changePage(engine, current, PageDirection.NEXT).snapshot }
        val candidate = window.snapshot(current).candidates[5]
        val update = window.select(engine, checkNotNull(window.route(candidate.id)), current)
        assertEquals("entry5", update.committedText)
    }

    @Test fun `a changed page cannot silently select another candidate`() {
        val engine = Pages()
        val window = CandidateWindow()
        val current = window.changePage(engine, engine.snapshot, PageDirection.NEXT).snapshot
        val candidate = window.snapshot(current).candidates.first()
        val route = checkNotNull(window.route(candidate.id))
        engine.changed = true
        assertFalse(window.select(engine, route, current).consumed)
        assertEquals(0, engine.selections)
    }

    private class Pages : InputEngine {
        private var page = 0
        var selections = 0
        var changed = false
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE))
        override val snapshot get() = EngineSnapshot("ni", "ni", List(4) {
            val id = page * 4 + it
            Candidate("entry$id", if (changed) "changed$id" else "entry$id")
        }, hasNextPage = page < 19, hasPreviousPage = page > 0)
        override fun start(context: EditorContext) = reset()
        override fun handle(key: EngineKey) = EngineUpdate(snapshot, consumed = false)
        override fun selectCandidate(index: Int): EngineUpdate {
            selections++
            return EngineUpdate(EngineSnapshot.Empty, snapshot.candidates[index].text)
        }
        override fun changePage(direction: PageDirection): EngineUpdate {
            page = (page + if (direction == PageDirection.NEXT) 1 else -1).coerceIn(0, 19)
            return EngineUpdate(snapshot)
        }
        override fun reset(): EngineSnapshot { page = 0; return snapshot }
        override fun close() = Unit
    }
}
