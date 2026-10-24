package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class AiProtocolReasoningTest {
    @Test fun jsonProtocolsKeepReasoningOutsideTheAnswer() {
        val cases = listOf(
            AiProtocol.CHAT_COMPLETIONS to """{"choices":[{"finish_reason":"stop","message":{"content":"answer","reasoning_content":"chat reasoning"}}]}""",
            AiProtocol.RESPONSES to """{"status":"completed","error":null,"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"responses reasoning"}]},{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"answer"}]}]}""",
            AiProtocol.ANTHROPIC_MESSAGES to """{"type":"message","role":"assistant","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"anthropic reasoning"},{"type":"text","text":"answer"}]}""",
        )
        val expected = listOf("chat reasoning", "responses reasoning", "anthropic reasoning")

        cases.forEachIndexed { index, (protocol, wire) ->
            val thinking = StringBuilder()
            assertEquals("answer", read(protocol, wire, json = true, thinking = thinking))
            assertEquals(expected[index], thinking.toString())
        }
    }

    @Test fun streamingProtocolsKeepReasoningDeltasOutsideTheAnswer() {
        val chat = listOf(
            """{"choices":[{"delta":{"reasoning_content":"chat reasoning"}}]}""",
            """{"choices":[{"delta":{"content":"answer"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val responseFinal = """{"status":"completed","error":null,"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"responses reasoning"}]},{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"answer"}]}]}"""
        val responses = "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"responses reasoning\"}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":$responseFinal}\n\n"
        val anthropic = listOf(
            """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"anthropic reasoning"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        val cases = listOf(
            AiProtocol.CHAT_COMPLETIONS to chat,
            AiProtocol.RESPONSES to responses,
            AiProtocol.ANTHROPIC_MESSAGES to anthropic,
        )
        val expected = listOf("chat reasoning", "responses reasoning", "anthropic reasoning")

        cases.forEachIndexed { index, (protocol, wire) ->
            val thinking = StringBuilder()
            assertEquals("answer", read(protocol, wire, json = false, thinking = thinking))
            assertEquals(expected[index], thinking.toString())
        }
    }

    private fun read(protocol: AiProtocol, wire: String, json: Boolean, thinking: StringBuilder): String =
        AiProtocolResponse.read(protocol, json, StringReader(wire).buffered(), { false }, {}, thinking::append)
}
