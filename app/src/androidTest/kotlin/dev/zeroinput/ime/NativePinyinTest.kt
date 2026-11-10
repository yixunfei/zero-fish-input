package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
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
    fun mixedInitialAndNearSyllableOfferTheWholePhrase() = withEngine { engine ->
        for ((raw, learning) in listOf("nmen" to "nmen", "nmeng" to "nimen")) {
            type(engine, raw)
            val snapshot = engine.snapshot
            val index = snapshot.candidates.indexOfFirst { it.text == "你们" }
            assertTrue("Expected the whole phrase for $raw among: ${snapshot.candidates.map { it.text }}", index >= 0)
            if (raw == "nmeng") assertTrue(snapshot.candidates.first().text == "柠檬")
            assertTrue(snapshot.rawInput == raw)
            assertTrue(snapshot.candidates[index].pinyinMatch?.end == raw.length)
            val update = engine.selectCandidate(index)
            assertTrue(update.committedText == "你们" && !update.snapshot.isComposing)
            assertTrue(update.learningInput == learning)
        }
        type(engine, "nmeng")
        assertTrue(engine.handle(EngineKey.Enter).committedText == "nmeng")
    }

    @Test
    fun mixedRecoveryRequiresAbbreviatedPinyin() = withEngine(ChineseInputOptions(abbreviatedPinyin = false)) { engine ->
        type(engine, "nmeng")
        assertFalse(engine.snapshot.candidates.any { it.text == "你们" })
    }

    @Test
    fun alternateSplitFillsTheCandidateListWithoutEditingTheRawInput() = withEngine { engine ->
        type(engine, "kanga")
        val initial = engine.snapshot
        assertTrue("Primary segmentation must remain first", initial.candidates.first().pinyinMatch?.spelling == "kang a")
        val alternate = initial.candidates.indexOfFirst { it.pinyinMatch?.spelling == "kan ga" }
        assertTrue("An alternate whole reading must be available on the first page", alternate > 0)
        assertTrue("A projected reading must not insert a delimiter into the user's input", initial.rawInput == "kanga")
        val selected = engine.selectCandidate(alternate)
        assertTrue("Selecting the projected reading must commit exactly the visible word",
            selected.committedText == initial.candidates[alternate].text && !selected.snapshot.isComposing)
        assertTrue("Learning must use the unmodified code", selected.learningInput == "kanga")

        type(engine, "kang'a")
        assertTrue("Explicit syllable boundaries must remain in the raw input", engine.snapshot.rawInput == "kang'a")
        assertFalse(engine.snapshot.candidates.any { it.pinyinMatch?.spelling == "kan ga" })
        assertTrue(engine.handle(EngineKey.Backspace).snapshot.rawInput == "kang'")
        engine.reset()
        type(engine, "kanga")
        assertTrue(engine.handle(EngineKey.Enter).committedText == "kanga")
    }

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

    private fun withEngine(options: ChineseInputOptions = ChineseInputOptions(), action: (InputEngine) -> Unit) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication
        val graph = application.graph
        graph.engineExecutor.submit {
            check(graph.rime.runtime.isReady) { "Native Rime must be ready for integration tests" }
            checkNotNull(graph.rime.createNativeOrNull(options)).use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                action(engine)
            }
        }.get(60, TimeUnit.SECONDS)
    }
}
