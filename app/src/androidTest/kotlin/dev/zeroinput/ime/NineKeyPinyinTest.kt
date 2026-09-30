package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.engine.api.ReadingSelectionEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NineKeyPinyinTest {
    @Test fun nineKeyCompositionLimitReturnsAdditionalInputToTheController() = withEngine { engine ->
        val input = "64".repeat(64)
        type(engine, input)
        assertEquals(input, engine.snapshot.rawInput)
        val before = engine.snapshot
        val overflow = engine.handle(EngineKey.Character("4"))
        assertFalse(overflow.consumed)
        assertEquals("", overflow.committedText)
        assertEquals(before, overflow.snapshot)
        assertTrue(engine.handle(EngineKey.Backspace).consumed)
        assertEquals(input.dropLast(1), engine.snapshot.rawInput)
        assertTrue(engine.handle(EngineKey.Character("4")).consumed)
        assertEquals(input, engine.snapshot.rawInput)
    }

    @Test fun numericPinyinOffersAndCommitsThePhrase() = withEngine { engine ->
        type(engine, "64426")
        val index = engine.snapshot.candidates.indexOfFirst { it.text == "你好" }
        assertTrue("Nine-key fixture phrase must appear on the first page", index >= 0)
        assertTrue(engine.selectCandidate(index).committedText == "你好")
        assertFalse(engine.snapshot.isComposing)
        assertTrue(engine.snapshot.readings.isEmpty())
    }

    @Test fun readingSelectionCanBeUndoneBeforeContinuingInput() = withEngine { engine ->
        type(engine, "64426")
        val readings = engine as ReadingSelectionEngine
        assertFalse(readings.selectReading(-1).consumed)
        select(engine, "ni")
        assertTrue(engine.snapshot.rawInput == "ni'426")
        engine.handle(EngineKey.Backspace)
        assertTrue("Backspace must restore unresolved digits", engine.snapshot.rawInput == "64426")
        select(engine, "ni")
        select(engine, "hao")
        assertTrue(engine.snapshot.rawInput == "ni'hao'")
        assertTrue(engine.handle(EngineKey.Space).committedText == "你好")
        type(engine, "426")
        select(engine, "hao")
        engine.reset()
        assertFalse(engine.handle(EngineKey.Backspace).consumed)
        assertTrue(engine.snapshot.readings.isEmpty())
    }

    @Test fun pagingAndPartialSelectionDoNotRewriteFixedText() = withEngine { engine ->
        type(engine, "64")
        assertTrue(engine.snapshot.hasNextPage)
        engine.changePage(PageDirection.NEXT)
        assertTrue(engine.snapshot.rawInput == "64")
        assertTrue(engine.snapshot.hasPreviousPage)
        engine.reset()
        type(engine, "64426")
        select(engine, "ni")
        select(engine, "hao")
        val partial = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        assertTrue(partial >= 0)
        engine.selectCandidate(partial)
        assertTrue("Reading changes must not overwrite a fixed candidate", engine.snapshot.readings.isEmpty())
        assertFalse((engine as ReadingSelectionEngine).selectReading(0).consumed)
        val raw = engine.snapshot.rawInput
        assertEquals(raw, engine.handle(EngineKey.Enter).committedText)
    }

    private fun select(engine: InputEngine, reading: String) {
        val index = engine.snapshot.readings.indexOf(reading)
        assertTrue("Public fixture reading must be offered", index >= 0)
        assertTrue((engine as ReadingSelectionEngine).selectReading(index).consumed)
    }

    private fun type(engine: InputEngine, input: String) = input.forEach { engine.handle(EngineKey.Character(it.toString())) }

    private fun withEngine(action: (InputEngine) -> Unit) {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            val options = ChineseInputOptions(keyboardLayout = ChineseKeyboardLayout.NINE_KEY)
            try {
                checkNotNull(graph.rime.createNativeOrNull(options)).use { engine ->
                    engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    action(engine)
                }
            } finally {
                checkNotNull(graph.rime.createNativeOrNull()).close()
            }
        }.get(60, TimeUnit.SECONDS)
    }
}
