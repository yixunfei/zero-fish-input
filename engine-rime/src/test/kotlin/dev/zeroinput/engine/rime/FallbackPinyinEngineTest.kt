package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.FuzzyPinyinPair
import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPinyinEngineTest {
    @Test
    fun `extended fuzzy pairs remain available on the immediate engine`() {
        val options = ChineseInputOptions()
            .withFuzzy(FuzzyPinyinPair.N_L, true)
            .withFuzzy(FuzzyPinyinPair.ON_ONG, true)
        val engine = FallbackPinyinEngine(options)

        engine.restoreComposition("li")
        assertTrue(engine.snapshot.candidates.any { it.text == "你" })
        engine.restoreComposition("gon")
        assertTrue(engine.snapshot.candidates.any { it.text == "公" })
    }

    @Test
    fun `shared initial fuzzy pairs preserve canonical h syllables`() {
        val options = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.Z_ZH, true)
        val variants = FuzzyPinyinMatcher.variants("zhe", options, setOf("zhe"))

        assertTrue("zhe" in variants)
        assertTrue("ze" in variants)
        assertFalse("zhhe" in variants)
    }

    @Test fun `input beyond the composition bound is returned to the controller without silent consumption`() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("a".repeat(128))
        val update = engine.handle(EngineKey.Character("b"))
        assertFalse(update.consumed)
        assertEquals(128, update.snapshot.rawInput.length)
    }

    @Test fun `paging reaches every prefix result and stops without repeats`() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("z")
        val seen = mutableSetOf<String>()
        var pages = 0
        do {
            for (candidate in engine.snapshot.candidates) assertTrue(seen.add(candidate.text))
            pages++
        } while (engine.changePage(dev.zeroinput.engine.api.PageDirection.NEXT).consumed)
        assertTrue(pages > 1)
        assertTrue(seen.size > 8)
        assertFalse(engine.snapshot.hasNextPage)
    }

    @Test fun `complete syllables do not repeat candidates from shorter completion prefixes`() {
        val engine = FallbackPinyinEngine(ChineseInputOptions(candidatePageSize = 5))
        engine.restoreComposition("ni")
        val seen = mutableSetOf<String>()
        do {
            for (candidate in engine.snapshot.candidates) assertTrue(seen.add(candidate.text))
        } while (engine.changePage(dev.zeroinput.engine.api.PageDirection.NEXT).consumed)
        assertEquals(setOf("你", "呢", "尼", "你好"), seen)
    }

    @Test fun `partial selection only consumes a complete reading before unmatched input`() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("nix")
        assertEquals(listOf("你", "呢", "尼"), engine.snapshot.candidates.map { it.text })
        val update = engine.selectCandidate(0)
        assertEquals("你x", update.snapshot.composition)
        assertTrue(update.committedText.isEmpty())
        assertTrue(update.committedInput.isEmpty())
    }

    @Test fun `fuzzy segment selections learn canonical readings and preserve remaining input`() {
        val options = ChineseInputOptions()
            .withFuzzy(FuzzyPinyinPair.N_L, true)
            .withFuzzy(FuzzyPinyinPair.ON_ONG, true)
        val engine = FallbackPinyinEngine(options)
        engine.restoreComposition("li'ai'gon")
        engine.selectSyllable()
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "你" })
        assertEquals("你ai'gon", engine.snapshot.composition)
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "爱" })
        val update = engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "公" })
        assertEquals("你爱公", update.committedText)
        assertEquals("niaigong", update.committedInput)
        assertTrue(update.learnable)
        assertFalse(update.snapshot.isComposing)
    }

    @Test fun `unknown phrase supports segment undo and complete canonical learning`() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("ni'ai'hao")
        engine.selectSyllable()
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "你" })
        assertEquals("你ai'hao", engine.snapshot.composition)
        assertTrue(engine.snapshot.canUndoSelection)
        engine.undoSelection()
        assertEquals("ni'ai'hao", engine.snapshot.composition)
        engine.selectSyllable()
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "你" })
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "爱" })
        val update = engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "浩" })
        assertEquals("你爱浩", update.committedText)
        assertEquals("niaihao", update.committedInput)
        assertTrue(update.learnable)
        assertFalse(update.snapshot.isComposing)
    }

    private val context = EditorContext(
        language = InputLanguage.CHINESE,
        isSensitive = false,
        learningAllowed = true,
        packageName = "test",
    )

    @Test
    fun `enter commits a candidate without manufacturing an editor newline`() {
        val engine = FallbackPinyinEngine()
        engine.start(context)
        "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }

        val update = engine.handle(EngineKey.Enter)

        assertTrue(update.consumed)
        assertEquals("你好", update.committedText)
        assertFalse(update.snapshot.isComposing)
    }

    @Test
    fun `enter declines unknown composition so controller can commit it and send enter`() {
        val engine = FallbackPinyinEngine()
        engine.start(context)
        "qz".forEach { engine.handle(EngineKey.Character(it.toString())) }

        val update = engine.handle(EngineKey.Enter)

        assertFalse(update.consumed)
        assertEquals("qz", update.snapshot.rawInput)
        assertTrue(update.snapshot.isComposing)
    }

    @Test
    fun `uppercase pinyin remains composing in Chinese mode`() {
        val engine = FallbackPinyinEngine()
        engine.start(context)

        "NI".forEach { engine.handle(EngineKey.Character(it.toString())) }

        assertEquals("ni", engine.snapshot.rawInput)
        assertTrue(engine.snapshot.isComposing)
        assertEquals("你", engine.handle(EngineKey.Space).committedText)
    }

    @Test
    fun `space confirms composition and only inserts whitespace when idle`() {
        val engine = FallbackPinyinEngine()
        engine.start(context)
        "nihao".forEach { engine.handle(EngineKey.Character(it.toString())) }

        val update = engine.handle(EngineKey.Space)

        assertEquals("你好", update.committedText)
        assertFalse(update.snapshot.isComposing)
        assertEquals(" ", engine.handle(EngineKey.Space).committedText)
    }
}
