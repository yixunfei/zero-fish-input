package dev.zeroinput.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiDraftSelectionTest {
    @Test fun acceptsOnlyTheCurrentNonblankDraftRange() {
        val draft = "first 😀 selected third"
        val start = draft.indexOf("selected")
        assertEquals("selected", validatedAiDraftSelection(draft, draft, start, start + 8))
    }

    @Test fun rejectsStaleSourceInvalidRangeAndSurrogateSplits() {
        val draft = "a😀b"
        assertNull(validatedAiDraftSelection("old", draft, 0, 1))
        assertNull(validatedAiDraftSelection(draft, draft, -1, 1))
        assertNull(validatedAiDraftSelection(draft, draft, 2, 3))
        assertNull(validatedAiDraftSelection(draft, draft, 1, 2))
        assertNull(validatedAiDraftSelection(draft, draft, 0, draft.length + 1))
        assertNull(validatedAiDraftSelection(draft, draft, 0, 0))
        assertNull(validatedAiDraftSelection("  ", "  ", 0, 2))
    }
}
