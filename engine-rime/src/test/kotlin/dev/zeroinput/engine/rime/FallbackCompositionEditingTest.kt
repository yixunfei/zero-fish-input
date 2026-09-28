package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.EngineKey
import org.junit.Assert.*
import org.junit.Test

class FallbackCompositionEditingTest {
    @Test fun deletingNewInputAfterSegmentSelectionPreservesTheSelectedSegment() {
        val engine = FallbackPinyinEngine()
        engine.restoreComposition("nihao")
        engine.selectSyllable()
        engine.selectCandidate(engine.snapshot.candidates.indexOfFirst { it.text == "你" })
        engine.handle(EngineKey.Character("x"))
        val update = engine.handle(EngineKey.Backspace)
        assertEquals("nihao", update.snapshot.rawInput)
        assertTrue(update.snapshot.composition.startsWith("你"))
        assertTrue(update.snapshot.canUndoSelection)
        assertTrue(update.committedText.isEmpty())
        assertFalse(engine.handle(EngineKey.Backspace).snapshot.canUndoSelection)
    }
}
