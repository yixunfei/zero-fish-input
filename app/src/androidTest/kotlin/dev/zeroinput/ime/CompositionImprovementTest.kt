package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CompositionImprovementTest {
    @Test fun exhaustedExactCandidatesContinueWithRelatedReadingsAndCanReturnToTheOriginalPage() =
        withEngine(ChineseInputOptions()) { engine ->
            type(engine, "neng")
            val original = engine.snapshot.rawInput
            var pages = 0
            while (engine.snapshot.candidates.none { it.id.startsWith("related:") } &&
                engine.snapshot.hasNextPage && pages++ < 100) {
                engine.changePage(PageDirection.NEXT)
                // Related sessions are created lazily on the engine worker. Let that
                // request finish before pressing Next again; never occupy its worker.
                graph.engineExecutor.submit {}.get(60, TimeUnit.SECONDS)
            }
            assertTrue("Related reading must follow exact candidates", engine.snapshot.candidates.any { it.id.startsWith("related:") })
            assertEquals(original, engine.snapshot.rawInput)
            val relatedText = engine.snapshot.candidates.first().text
            engine.changePage(PageDirection.PREVIOUS)
            assertFalse(engine.snapshot.candidates.first().id.startsWith("related:"))
            engine.changePage(PageDirection.NEXT)
            assertEquals(relatedText, engine.snapshot.candidates.first().text)
            val selected = engine.selectCandidate(0)
            assertEquals(relatedText, selected.committedText)
            assertTrue(selected.committedInput.isNotBlank())
            assertTrue(selected.learnable)
        }

    @Test fun syllableSelectionAndUndoPreserveTheWholeReadingForLearning() = withEngine(ChineseInputOptions()) { engine ->
        val editing = engine as CompositionEditingEngine
        type(engine, "nihao")
        editing.selectSyllable()
        var index = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        assertTrue(index >= 0)
        val partial = engine.selectCandidate(index)
        assertTrue(partial.committedText.isEmpty())
        assertTrue(partial.snapshot.canUndoSelection)
        val undone = editing.undoSelection()
        assertEquals("nihao", undone.snapshot.rawInput)
        assertFalse(undone.snapshot.canUndoSelection)
        editing.selectSyllable()
        index = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        engine.selectCandidate(index)
        index = engine.snapshot.candidates.indexOfFirst { it.text == "好" }
        assertTrue(index >= 0)
        val committed = engine.selectCandidate(index)
        assertEquals("你好", committed.committedText)
        assertEquals("nihao", committed.committedInput)
        assertTrue(committed.learnable)
        assertTrue(editing.restoreComposition(committed.committedInput).snapshot.isComposing)
    }

    @Test fun experimentalSpellingOffersCorrectedPhrasesAndPreservesCanonicalLearningInput() =
        withEngine(ChineseInputOptions(experimentalTypoCorrection = true)) { engine ->
            for (input in listOf("nihso", "nihoa", "nihaao")) {
                engine.reset()
                type(engine, input)
                var index = engine.snapshot.candidates.indexOfFirst { it.text == "你好" }
                var pages = 0
                while (index < 0 && engine.snapshot.hasNextPage && pages++ < 12) {
                    engine.changePage(PageDirection.NEXT)
                    index = engine.snapshot.candidates.indexOfFirst { it.text == "你好" }
                }
                assertTrue("Corrected fixture must be available", index >= 0)
                val selected = engine.selectCandidate(index)
                assertEquals("你好", selected.committedText)
                assertEquals("nihao", selected.committedInput)
            }
        }

    private fun type(engine: InputEngine, input: String) { input.forEach { engine.handle(EngineKey.Character(it.toString())) } }
    private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
    private fun withEngine(options: ChineseInputOptions, action: (InputEngine) -> Unit) {
        val engine = graph.engineExecutor.submit<InputEngine> {
            check(graph.rime.runtime.isReady)
            checkNotNull(graph.rime.createNativeOrNull(options))
        }.get(60, TimeUnit.SECONDS)
        engine.use {
            it.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            action(it)
        }
    }
}
