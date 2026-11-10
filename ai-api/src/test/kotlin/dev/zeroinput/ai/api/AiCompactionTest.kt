package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiCompactionTest {
    @Test fun sourceContainsOnlyEarlierSelectedTurnsAndExistingSummaries() {
        val messages = List(6) { AiMessage(if (it % 2 == 0) AiRole.USER else AiRole.ASSISTANT, "public $it") }
        val summary = AiReference("older summary", AiReference.Source.SUMMARY)
        val state = AiContextState(7, messages, setOf(0, 2, 4, 5), listOf(summary, AiReference("page")))
        val input = AiCompactionInput(state)
        assertEquals(7L, input.revision); assertEquals(setOf(0, 2), input.indices)
        assertEquals(listOf(messages[0], messages[2]), input.history)
        assertEquals(listOf(summary), input.summaries)
    }

    @Test fun emptySourcesAndInvalidSummariesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { AiCompactionInput(AiContextState()) }
        for (text in listOf("", "x".repeat(4_097), "public\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { AiCompactionProposal(0, text) }
        }
        assertFalse(AiCompactionProposal(0, "public fixture").toString().contains("public fixture"))
    }
}
