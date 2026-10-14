package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CompositionEditingRegressionTest {
    @Test fun splitAtTheCaretInsertsAnApostropheWithoutCommittingOrLosingTheSuffix() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        editing.restoreComposition("ch")
        (engine as CompositionCursorEngine).selectComposition(CompositionSelection(1))
        val split = editing.selectSyllable()
        assertEquals("c'h", split.snapshot.rawInput)
        assertEquals(CompositionSelection(2), split.snapshot.selection)
        assertTrue(split.committedText.isEmpty())
        assertEquals("ch", engine.handle(EngineKey.Backspace).snapshot.rawInput)
    }

    @Test fun cursorInsertionRangeReplacementAndDeletionStayInTheComposition() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        val cursor = engine as CompositionCursorEngine
        editing.restoreComposition("chaxun")
        cursor.selectComposition(CompositionSelection(1))
        val inserted = engine.handle(EngineKey.Character("x"))
        assertEquals("cxhaxun", inserted.snapshot.rawInput)
        assertEquals(CompositionSelection(2), inserted.snapshot.selection)
        assertTrue(inserted.committedText.isEmpty())
        assertEquals("chaxun", engine.handle(EngineKey.Backspace).snapshot.rawInput)
        cursor.selectComposition(CompositionSelection(3, 1))
        assertEquals("cexun", engine.handle(EngineKey.Character("e")).snapshot.rawInput)
        cursor.selectComposition(CompositionSelection(0))
        val boundary = engine.handle(EngineKey.Backspace)
        assertTrue(boundary.consumed)
        assertEquals("cexun", boundary.snapshot.rawInput)
        cursor.selectComposition(CompositionSelection(0, 5))
        val empty = engine.handle(EngineKey.Backspace)
        assertFalse(empty.snapshot.isComposing)
        assertTrue(empty.committedText.isEmpty())
    }

    @Test fun cursorEditingSelectedSegmentsRestoresReadingAndCandidateBrowsingKeepsCursor() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        val cursor = engine as CompositionCursorEngine
        editing.restoreComposition("nihao")
        editing.selectSyllable()
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "你" })
        assertTrue(engine.snapshot.canUndoSelection)
        cursor.selectComposition(CompositionSelection(1))
        assertEquals("nihao", engine.snapshot.rawInput)
        assertFalse(engine.snapshot.canUndoSelection)
        assertEquals("nihao", engine.snapshot.composition.filterNot { it == ' ' || it == '\'' })
        engine.changePage(PageDirection.NEXT)
        assertEquals(CompositionSelection(1), engine.snapshot.selection)
        assertEquals("nxihao", engine.handle(EngineKey.Character("x")).snapshot.rawInput)
    }

    @Test fun repeatedBackspaceAfterSyllableNavigationNeverFallsThroughToTheEditor() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        editing.restoreComposition("nihaoshijie")
        editing.selectSyllable()
        repeat(16) {
            if (engine.snapshot.isComposing) {
                val update = engine.handle(EngineKey.Backspace)
                assertTrue("Composing backspace must stay inside the engine", update.consumed)
                assertTrue("Deletion must never commit the preedit", update.committedText.isEmpty())
            }
        }
        assertFalse("Repeated deletion must empty the composition", engine.snapshot.isComposing)
    }

    @Test fun deletingAnAppendedLetterKeepsTheSelectedSegment() = withEngine { engine ->
        val editing = engine as CompositionEditingEngine
        editing.restoreComposition("nihao")
        editing.selectSyllable()
        val index = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        assertTrue(index >= 0)
        engine.selectCandidate(index)
        engine.handle(EngineKey.Character("x"))
        val result = engine.handle(EngineKey.Backspace)
        assertEquals("nihao", result.snapshot.rawInput)
        assertTrue(result.snapshot.canUndoSelection)
        assertTrue(result.committedText.isEmpty())
    }

    private fun withEngine(action: (InputEngine) -> Unit) {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            checkNotNull(graph.rime.createNativeOrNull()).use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                action(engine)
            }
        }.get(60, TimeUnit.SECONDS)
    }
}
