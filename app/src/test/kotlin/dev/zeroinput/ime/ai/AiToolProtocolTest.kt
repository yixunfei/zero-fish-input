package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiToolProtocolTest {
    private val tool = AiToolDefinition("files/read", "read", "Read", "Read public fixture",
        """{"type":"object","properties":{"file":{"type":"string"}},"required":["file"],"additionalProperties":false}""", AiToolOrigin.FILE, AiToolEffect.READ)
    @Test fun protocolsKeepSelectedTextAndToolResultsOutOfSystemAuthority() {
        val call = AiToolCall("call", "read", """{"file":"public"}""")
        val request = AiToolRequest("Public task", listOf(tool), AiWorkspaceContext(skills = listOf(
            AiSkill("local-id", "Public recipe", "", "Public instructions", listOf("files/read")))),
            listOf(AiToolTurn(call, AiToolResult(call.id, AiToolResultStatus.SUCCESS, "Untrusted public result"))))
        for (protocol in AiProtocol.entries) {
            val encoded = AiToolProtocol.encode(request, "fixture", protocol)
            val root = AiToolJson.objectValue(encoded, 262_144)
            assertFalse(encoded.contains("local-id")); assertTrue(encoded.contains("untrusted_result"))
            assertEquals("read", root.getJSONArray("tools").getJSONObject(0).let {
                if (protocol == AiProtocol.CHAT_COMPLETIONS) it.getJSONObject("function").getString("name") else it.getString("name")
            })
            val system = when (protocol) {
                AiProtocol.CHAT_COMPLETIONS -> root.getJSONArray("messages").getJSONObject(0).getString("content")
                AiProtocol.RESPONSES -> root.getString("instructions")
                AiProtocol.ANTHROPIC_MESSAGES -> root.getString("system")
            }
            assertFalse(system.contains("Public instructions")); assertFalse(system.contains("Untrusted public result"))
        }
    }
    @Test fun allProtocolsAcceptSingleNativeCallsButRejectParallelOrIncompleteResponses() {
        val chat = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call","type":"function","function":{"name":"read","arguments":"{\"file\":\"public\"}"}}]}}]}"""
        val responses = """{"status":"completed","output":[{"type":"function_call","call_id":"call","name":"read","arguments":"{\"file\":\"public\"}"}]}"""
        val anthropic = """{"type":"message","role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"call","name":"read","input":{"file":"public"}}]}"""
        for ((protocol, raw) in listOf(AiProtocol.CHAT_COMPLETIONS to chat, AiProtocol.RESPONSES to responses,
            AiProtocol.ANTHROPIC_MESSAGES to anthropic)) {
            val root = AiToolJson.objectValue(raw)
            assertEquals("read", AiToolProtocol.decode(root, protocol).call!!.name)
            val array = when (protocol) {
                AiProtocol.CHAT_COMPLETIONS -> root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls")
                AiProtocol.RESPONSES -> root.getJSONArray("output")
                AiProtocol.ANTHROPIC_MESSAGES -> root.getJSONArray("content")
            }
            array.put(array.getJSONObject(0))
            assertTrue(runCatching { AiToolProtocol.decode(root, protocol) }.isFailure)
        }
        assertTrue(runCatching { AiToolProtocol.decode(JSONObject(responses.replace("completed", "incomplete")), AiProtocol.RESPONSES) }.isFailure)
        assertTrue(runCatching { AiToolProtocol.decode(JSONObject(chat.replace("tool_calls\",\"message", "length\",\"message")), AiProtocol.CHAT_COMPLETIONS) }.isFailure)
    }
    @Test fun strictSchemasAndArgumentsRejectExtensionsDuplicateKeysAndDeepStructures() {
        for (raw in listOf("{\"type\":\"object\",\"type\":\"object\"}",
            "{\"type\":\"object\",\"additionalProperties\":true}",
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\".*\"}}}",
            "{type:'object'}", "{\"type\":\"object\",\"required\":[\"missing\"]}")) {
            assertTrue(runCatching { AiToolJson.schema(raw) }.isFailure)
        }
        assertTrue(runCatching { AiToolJson.objectValue("{\"x\":" + "[".repeat(100) + "]".repeat(100) + "}") }.isFailure)
        assertTrue(runCatching { AiToolJson.arguments(AiToolJson.schema(tool.schema), "{\"file\":null}") }.isFailure)
    }
}
