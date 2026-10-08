package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test

class AiContextSelectionTest {
    @Test fun recentSkipsSystemMessagesWithoutSpendingMessageOrCharacterBudget() {
        val selection = AiContextSelection()
        selection.reset(listOf(AiMessage(AiRole.USER, "earlier")) +
            List(12) { AiMessage(AiRole.SYSTEM, "ignored") } + AiMessage(AiRole.ASSISTANT, "latest"))
        assertTrue(selection.recent(selection.state.revision))
        assertEquals(listOf("earlier", "latest"), selection.history().map { it.content })
    }

    @Test fun recentKeepsContiguousEligibleSuffixWithinCombinedBudgetAndRejectsStaleRevision() {
        val selection = AiContextSelection()
        selection.reset(listOf(AiMessage(AiRole.USER, "old"),
            AiMessage(AiRole.ASSISTANT, "x".repeat(12_288)), AiMessage(AiRole.USER, "new")))
        selection.add(selection.state.revision, listOf(AiReference("r".repeat(4096))))
        val stale = selection.state.revision
        assertTrue(selection.recent(stale))
        assertEquals(listOf("new"), selection.history().map { it.content })
        assertFalse(selection.recent(stale))
    }

    @Test fun openingHistorySelectsNothingAndExplicitSelectionKeepsChronologicalOrder() {
        val selection = AiContextSelection()
        selection.reset(listOf(AiMessage(AiRole.USER, "first"), AiMessage(AiRole.ASSISTANT, "second")))
        assertTrue(selection.history().isEmpty())
        assertTrue(selection.include(selection.state.revision, 1, true))
        assertTrue(selection.include(selection.state.revision, 0, true))
        assertEquals(listOf("first", "second"), selection.history().map { it.content })
        assertTrue(selection.include(selection.state.revision, 0, false))
        assertEquals(listOf("second"), selection.history().map { it.content })
        assertEquals(2, selection.state.messages.size)
    }

    @Test fun staleCheckboxCannotChangeAnotherConversation() {
        val selection = AiContextSelection()
        selection.reset(listOf(AiMessage(AiRole.USER, "first")))
        val stale = selection.state.revision
        selection.reset(listOf(AiMessage(AiRole.USER, "new")))
        assertFalse(selection.include(stale, 0, true))
        assertFalse(selection.add(stale, listOf(AiReference("old page"))))
        assertTrue(selection.history().isEmpty())
        assertTrue(selection.state.references.isEmpty())
    }

    @Test fun oversizedCombinedContextRejectsEntireChangeAndDoesNotSilentlyTruncate() {
        val selection = AiContextSelection()
        selection.reset(listOf(AiMessage(AiRole.USER, "h".repeat(12_288))))
        assertTrue(selection.include(selection.state.revision, 0, true))
        assertTrue(selection.add(selection.state.revision, listOf(AiReference("r".repeat(4096)))))
        val before = selection.state
        assertFalse(selection.add(before.revision, listOf(AiReference("extra"))))
        assertSame(before, selection.state)
        assertEquals(16_384, selection.state.characterCount)
    }

    @Test fun duplicateReferencesAreDeduplicatedAndPageRevocationKeepsExplicitImports() {
        val selection = AiContextSelection()
        selection.add(selection.state.revision, listOf(AiReference("page"), AiReference("page"),
            AiReference("import", AiReference.Source.IMPORT)))
        assertEquals(2, selection.state.references.size)
        assertTrue(selection.clearPages())
        assertEquals(listOf("import"), selection.state.references.map { it.text })
    }

    @Test fun systemMessagesAndTooManySelectionsCannotBecomeContext() {
        val selection = AiContextSelection()
        selection.reset(listOf(AiMessage(AiRole.SYSTEM, "untrusted instruction")))
        assertFalse(selection.include(selection.state.revision, 0, true))
        assertFalse(selection.add(selection.state.revision, List(17) { AiReference("block $it") }))
    }
}
