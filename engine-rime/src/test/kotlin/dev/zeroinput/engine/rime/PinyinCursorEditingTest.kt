package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class PinyinCursorEditingTest {
    @Test fun middleInsertionAndBackspaceKeepTheUneditedSuffix() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("chaxun")
        engine.selectComposition(CompositionSelection(1))
        assertEquals("chaxun", engine.snapshot.rawInput)
        assertEquals("cxhaxun", engine.handle(EngineKey.Character("x")).snapshot.rawInput)
        assertEquals(CompositionSelection(2), engine.snapshot.selection)
        assertEquals("chaxun", engine.handle(EngineKey.Backspace).snapshot.rawInput)
        assertEquals(CompositionSelection(1), engine.snapshot.selection)
        assertTrue(engine.snapshot.isComposing)
    }

    @Test fun reverseRangeCanBeReplacedAndDeletedWithoutCommitting() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("chaxun")
        engine.selectComposition(CompositionSelection(3, 1))
        val replaced = engine.handle(EngineKey.Character("e"))
        assertEquals("cexun", replaced.snapshot.rawInput)
        assertTrue(replaced.committedText.isEmpty())
        engine.selectComposition(CompositionSelection(0, 5))
        val deleted = engine.handle(EngineKey.Backspace)
        assertFalse(deleted.snapshot.isComposing)
        assertTrue(deleted.committedText.isEmpty())
    }

    @Test fun apostropheAtTheCaretKeepsTheSuffixAndShowsTheSplitReading() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("ch")
        engine.selectComposition(CompositionSelection(1))

        val update = engine.handle(EngineKey.Character("'"))

        assertEquals("c'h", update.snapshot.rawInput)
        assertEquals("c'h", update.snapshot.composition)
        assertEquals(CompositionSelection(2), update.snapshot.selection)
    }

    @Test fun splitAtThePositionedCaretInsertsAVisibleDelimiter() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("ch")
        engine.selectComposition(CompositionSelection(1))
        val update = engine.selectSyllable()
        assertEquals("c'h", update.snapshot.rawInput)
        assertEquals("c'h", update.snapshot.composition)
        assertEquals(CompositionSelection(2), update.snapshot.selection)
        assertTrue(update.committedText.isEmpty())
        assertEquals("ch", engine.handle(EngineKey.Backspace).snapshot.rawInput)
    }

    @Test fun replacingWithTheSameLetterCollapsesTheSelection() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("nihao")
        engine.selectComposition(CompositionSelection(1, 2))
        engine.handle(EngineKey.Character("i"))
        assertEquals("nihao", engine.snapshot.rawInput)
        assertEquals(CompositionSelection(2), engine.snapshot.selection)
    }

    @Test fun backspaceAtStartIsConsumedAndInvalidSelectionDoesNothing() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("nihao")
        engine.selectComposition(CompositionSelection(0))
        assertTrue(engine.handle(EngineKey.Backspace).consumed)
        assertEquals("nihao", engine.snapshot.rawInput)
        assertFalse(engine.selectComposition(CompositionSelection(-1, 100)).consumed)
        assertEquals(CompositionSelection(0), engine.snapshot.selection)
    }

    @Test fun editingRestoresSelectedSegmentsToTheirOriginalReading() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("nihao")
        engine.selectSyllable()
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "你" })
        assertTrue(engine.snapshot.canUndoSelection)
        engine.selectComposition(CompositionSelection(1, 2))
        assertFalse(engine.snapshot.canUndoSelection)
        assertEquals("nihao", engine.snapshot.composition)
        assertEquals("nhao", engine.handle(EngineKey.Backspace).snapshot.rawInput)
    }

    @Test fun boundedInputRejectsInsertionButAllowsReplacement() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("a".repeat(128))
        assertFalse(engine.handle(EngineKey.Character("b")).consumed)
        engine.selectComposition(CompositionSelection(1))
        assertTrue(engine.handle(EngineKey.Character("b")).consumed)
        assertEquals(128, engine.snapshot.rawInput.length)
        engine.selectComposition(CompositionSelection(1, 3))
        engine.handle(EngineKey.Character("b"))
        assertEquals("ab" + "a".repeat(125), engine.snapshot.rawInput)
    }

    @Test fun browsingKeepsTheCursorAndResetClearsIt() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("shi")
        engine.selectComposition(CompositionSelection(1))
        engine.changePage(PageDirection.NEXT)
        assertEquals(CompositionSelection(1), engine.snapshot.selection)
        assertNull(engine.reset().selection)
        assertNull(FallbackPinyinEngine(ChineseInputOptions(keyboardLayout = ChineseKeyboardLayout.NINE_KEY))
            .restoreComposition("ni").snapshot.selection)
    }
}
