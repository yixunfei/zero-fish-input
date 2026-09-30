package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineCapability
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import org.junit.Assert.*
import org.junit.Test

class DictionaryInputEngineTest {
    private val factory = DictionaryEngineFactory()
    private val context = EditorContext(InputLanguage.CHINESE, false, false, null)

    @Test fun `complete phrase selection and space clear all composition`() {
        factory.create().use { engine ->
            assertFalse(engine.start(context).isComposing)
            type(engine, "NI'HAO")
            assertEquals("你好", engine.snapshot.candidates.first().text)
            assertEquals("你好", engine.selectCandidate(0).committedText)
            assertFalse(engine.snapshot.isComposing)
            type(engine, "nihao")
            assertEquals("你好", engine.handle(EngineKey.Space).committedText)
            assertEquals(" ", engine.handle(EngineKey.Space).committedText)
            assertFalse(engine.handle(EngineKey.Enter).consumed)
            assertFalse(engine.handle(EngineKey.Backspace).consumed)
        }
    }

    @Test fun `paging preserves raw input and rejects invalid candidates and edges`() {
        for (size in ChineseInputOptions.PAGE_SIZES) factory.create(ChineseInputOptions(candidatePageSize = size)).use { engine ->
            engine.start(context)
            type(engine, "ni")
            assertEquals(size, engine.snapshot.candidates.size)
            val first = engine.snapshot
            assertFalse(engine.changePage(PageDirection.PREVIOUS).consumed)
            assertFalse(engine.selectCandidate(-1).consumed)
            assertFalse(engine.selectCandidate(size).consumed)
            assertEquals(first, engine.snapshot)
            assertTrue(engine.changePage(PageDirection.NEXT).consumed)
            assertEquals("ni", engine.snapshot.rawInput)
            assertTrue(engine.snapshot.hasPreviousPage)
            val expected = engine.snapshot.candidates.first().text
            assertEquals(expected, engine.selectCandidate(0).committedText)
            assertFalse(engine.snapshot.hasPreviousPage)
            assertFalse(engine.snapshot.hasNextPage)
        }
    }

    @Test fun `unknown input reset punctuation and close preserve the lifecycle contract`() {
        val engine = factory.create()
        engine.start(context)
        type(engine, "qqqx")
        engine.handle(EngineKey.Backspace)
        assertEquals("qqq", engine.handle(EngineKey.Enter).committedText)
        type(engine, "nihao")
        assertEquals("你好，", engine.handle(EngineKey.Character(",")).committedText)
        type(engine, "ni")
        assertFalse(engine.reset().isComposing)
        type(engine, "hao")
        assertEquals("hao", engine.handle(EngineKey.Enter).committedText)
        type(engine, "ni")
        engine.close()
        engine.close()
        assertFalse(engine.snapshot.isComposing)
        assertFalse(engine.handle(EngineKey.Character("n")).consumed)
        assertFalse(engine.selectCandidate(0).consumed)
        assertThrows(IllegalStateException::class.java) { engine.start(context) }
        factory.create(ChineseInputOptions(chinesePunctuation = false)).use {
            it.start(context)
            assertEquals(",", it.handle(EngineKey.Character(",")).committedText)
        }
    }

    @Test fun `input limit delegates overflow instead of swallowing a key`() {
        factory.create().use { engine ->
            engine.start(context)
            type(engine, "q".repeat(64))
            assertFalse(engine.handle(EngineKey.Character("q")).consumed)
            assertEquals(64, engine.snapshot.rawInput.length)
            assertFalse(EngineCapability.NINE_KEY_PINYIN in engine.descriptor.capabilities)
            assertFalse(EngineCapability.FUZZY_PINYIN in engine.descriptor.capabilities)
        }
    }

    private fun type(engine: InputEngine, value: String) = value.forEach { engine.handle(EngineKey.Character(it.toString())) }
}
