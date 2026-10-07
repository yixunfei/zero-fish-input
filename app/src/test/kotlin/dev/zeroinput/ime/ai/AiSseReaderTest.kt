package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiProviderError
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class AiSseReaderTest {
    private val stop = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
    private val done = "data: [DONE]\n\n"

    @Test fun doneWithoutNormalFinishAndContentAfterStopAreRejected() {
        for (wire in listOf(chunk("partial") + done, done, chunk("first") + stop + chunk("late") + done)) {
            assertThrows(AiProviderError.Response::class.java) { read(wire) }
        }
    }

    @Test fun toolPayloadsMultipleChoicesAndTrailingJsonAreRejected() {
        for (payload in listOf(
            """{"choices":[{"delta":{"content":"text","tool_calls":[{"id":"call"}]}}]}""",
            """{"choices":[{"delta":{"function_call":{"name":"action"}}}]}""",
            """{"choices":[{"delta":{"content":"one"}},{"delta":{"content":"two"}}]}""",
            """{"choices":[{"index":1,"delta":{"content":"other"}}]}""",
            """{"choices":[{"delta":{"content":"text"}}]} trailing""",
        )) {
            assertThrows(AiProviderError.Response::class.java) { read("data: $payload\n\n" + stop + done) }
        }
    }

    @Test fun multilineEventsAndTrailingUsagePreserveCompletedText() {
        val wire = "event: message\r\ndata: {\"choices\":[\r\ndata: {\"delta\":{\"content\":\"OK\"}}]}\r\n\r\n"
        assertEquals("OK", read(wire + stop + "data: {\"choices\":[],\"usage\":{}}\n\n" + done))
    }

    @Test fun exactLineLimitIsAcceptedAndOneAdditionalCharacterIsRejected() {
        val line = ":" + "x".repeat(AiLimits.MAX_STREAM_LINE_CHARS - 1)
        assertEquals("OK", read(line + "\n" + chunk("OK") + stop + done))
        val error = assertThrows(AiProviderError.Response::class.java) {
            read(line + "x\ndata: [DONE]\n")
        }
        assertEquals("AI stream line is too large", error.message)
    }

    private fun chunk(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"
    private fun read(value: String): String = AiSseReader().read(StringReader(value).buffered(), { false }, {})

    @Test fun multilingualDeltasStopAtDone() {
        assertEquals("你好 world", read(": keepalive\n" + chunk("你好") + chunk(" world") + stop + done + chunk("ignored")))
    }
    @Test fun acceptsBomAndOptionalFieldIndentation() {
        assertEquals("fixture", read("\uFEFF  data: {\"choices\":[{\"delta\":{\"content\":\"fixture\"}}]}\n\n" + stop + done))
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
        assertEquals("fixture", read(chunk("fixture") + terminal + done))
    }
}
