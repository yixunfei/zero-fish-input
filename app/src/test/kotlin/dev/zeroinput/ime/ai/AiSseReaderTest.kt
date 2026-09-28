package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiProviderError
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class AiSseReaderTest {
    private fun chunk(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"
    private fun read(value: String): String = AiSseReader().read(StringReader(value).buffered(), { false }, {})

    @Test fun multilingualDeltasStopAtDone() {
        assertEquals("你好 world", read(": keepalive\n" + chunk("你好") + chunk(" world") + "data: [DONE]\n" + chunk("ignored")))
    }
    @Test fun truncatedMalformedAndProviderErrorStreamsFailWithoutLeakingContent() {
        for (value in listOf(chunk("fixture"), "data: malformed secret\n", "data: {\"error\":\"secret\"}\n")) {
            val error = assertThrows(AiProviderError.Response::class.java) { read(value) }
            assertFalse(error.toString().contains("secret"))
        }
    }
    @Test fun limitsAndCancellationRejectUnboundedWork() {
        assertThrows(AiProviderError.Response::class.java) { read("x".repeat(AiLimits.MAX_STREAM_LINE_CHARS + 1)) }
        assertThrows(AiProviderError.Response::class.java) { read(chunk("x".repeat(8_192)).repeat(5)) }
        assertThrows(AiProviderError.Response::class.java) { read(":\n".repeat(AiLimits.MAX_STREAM_CHARS / 2 + 1)) }
        assertThrows(InterruptedException::class.java) {
            AiSseReader().read(StringReader(chunk("fixture")).buffered(), { true }, {})
        }
    }
    @Test fun incompleteOrNonTextFinishReasonsNeverProduceACompletedAnswer() {
        for (reason in listOf("length", "content_filter", "tool_calls", "function_call")) {
            val terminal = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n"
            assertThrows(AiProviderError.Response::class.java) {
                read(chunk("partial fixture") + terminal + "data: [DONE]\n")
            }
        }
    }
    @Test fun normalStopReasonPreservesACompletedTextAnswer() {
        val terminal = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        assertEquals("fixture", read(chunk("fixture") + terminal + "data: [DONE]\n"))
    }
}
