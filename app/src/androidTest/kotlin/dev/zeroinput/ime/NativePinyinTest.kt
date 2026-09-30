package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativePinyinTest {
    @Test
    fun completeCandidateSelectionCommitsAndClearsComposition() = withEngine { engine ->
        type(engine, "nihao")
        val index = engine.snapshot.candidates.indexOfFirst { it.text == "你好" }
        assertTrue("Native pinyin must offer the fixture candidate", index >= 0)
        val update = engine.selectCandidate(index)
        assertTrue("Candidate selection must commit the selected phrase", update.committedText == "你好")
        assertFalse("Committed candidate must leave no composition", update.snapshot.isComposing)
        assertFalse("Committed candidate must leave no next page", update.snapshot.hasNextPage)
        assertFalse("Committed candidate must leave no previous page", update.snapshot.hasPreviousPage)
    }

    @Test
    fun spaceCommitsPhraseAndPagingPreservesInput() = withEngine { engine ->
        type(engine, "ni")
        assertTrue("Native pinyin must support paging", engine.snapshot.hasNextPage)
        engine.changePage(PageDirection.NEXT)
        assertTrue("Paging must preserve input", engine.snapshot.rawInput == "ni")
        assertTrue(engine.snapshot.hasPreviousPage)
        engine.reset()
        type(engine, "nihao")
        val update = engine.handle(EngineKey.Space)
        assertTrue("Space must commit Chinese without a trailing space", update.committedText == "你好")
        assertFalse(update.snapshot.isComposing)
    }

    @Test
    fun enterCommitsRawInputAndIdleKeysRemainAvailableToEditor() = withEngine { engine ->
        type(engine, "nihao")
        val update = engine.handle(EngineKey.Enter)
        assertTrue("Enter must commit the original reading", update.committedText == "nihao")
        assertFalse(update.snapshot.isComposing)
        assertFalse(engine.handle(EngineKey.Enter).consumed)
        assertFalse(engine.handle(EngineKey.Backspace).consumed)
    }

    @Test
    fun partialSelectionKeepsRemainingSyllablesUntilFinalSelection() = withEngine { engine ->
        type(engine, "nihao")
        val index = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        assertTrue("Native pinyin must offer a partial candidate", index >= 0)
        val partial = engine.selectCandidate(index)
        assertTrue("Partial selection must not commit unfinished input", partial.committedText.isEmpty())
        assertTrue(partial.snapshot.isComposing)
        val finalIndex = partial.snapshot.candidates.indexOfFirst { it.text == "好" }
        assertTrue("Remaining syllables must still offer candidates", finalIndex >= 0)
        val complete = engine.selectCandidate(finalIndex)
        assertTrue("Final selection must commit the complete phrase", complete.committedText == "你好")
        assertFalse(complete.snapshot.isComposing)
    }

    @Test
    fun invalidSelectionBackspaceAndResetDoNotCommitOrLeakComposition() = withEngine { engine ->
        type(engine, "nihaox")
        val deleted = engine.handle(EngineKey.Backspace)
        assertTrue("Backspace must remove the last input character", deleted.snapshot.rawInput == "nihao")
        for (index in listOf(-1, 10000)) {
            val invalid = engine.selectCandidate(index)
            assertFalse(invalid.consumed)
            assertTrue(invalid.committedText.isEmpty())
            assertTrue("Invalid selection must preserve input", invalid.snapshot.rawInput == "nihao")
        }
        assertFalse(engine.reset().isComposing)
        type(engine, "hao")
        val update = engine.handle(EngineKey.Space)
        assertTrue("Reset must discard all previous input", update.committedText == "好")
        assertFalse(update.snapshot.isComposing)
    }

    private fun type(engine: InputEngine, input: String) {
        input.forEach { engine.handle(EngineKey.Character(it.toString())) }
    }

    private fun withEngine(action: (InputEngine) -> Unit) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication
        val graph = application.graph
        graph.engineExecutor.submit {
            check(graph.rime.runtime.isReady) { "Native Rime must be ready for integration tests" }
            checkNotNull(graph.rime.createNativeOrNull()).use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                action(engine)
            }
        }.get(60, TimeUnit.SECONDS)
    }
}
