package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiContextTest {
    @Test fun requestRejectsSystemHistoryAndContextOverflowBeforeTransport() {
        val system = listOf(AiMessage(AiRole.SYSTEM, "untrusted"))
        val oversized = listOf(AiMessage(AiRole.USER, "x".repeat(16_385)))
        val tooMany = List(13) { AiMessage(AiRole.USER, "x") }
        for (history in listOf(system, oversized, tooMany)) assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "question", history = history)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "question", references = List(17) { AiReference("$it") })
        }
    }

    @Test fun referenceBodiesAreBoundedPlainTextAndRedactedInDiagnostics() {
        for (invalid in listOf(" ", "x".repeat(4097), "nul\u0000value")) {
            assertThrows(IllegalArgumentException::class.java) { AiReference(invalid) }
        }
        val reference = AiReference("public quote\nsecond line")
        assertFalse(reference.toString().contains("public quote"))
        assertFalse(AiContextState(references = listOf(reference)).toString().contains("public quote"))
    }
}
