package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.AiProtocol
import dev.zeroinput.ai.api.AiProviderError
import dev.zeroinput.ai.api.DeviceActionCatalog
import dev.zeroinput.ai.api.DeviceAgentModelRequest
import dev.zeroinput.ai.api.DeviceElement
import dev.zeroinput.ai.api.DeviceBounds
import dev.zeroinput.ai.api.DeviceScreenSnapshot
import dev.zeroinput.ai.api.DeviceStage6ToolCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceAgentProtocolCodecTest {
    private val snapshot = DeviceScreenSnapshot(
        packageName = "com.example.app",
        windowId = 7,
        snapshotId = "snapshot-1",
        elements = listOf(
            DeviceElement(
                ref = "0.0",
                role = "Button",
                label = "Continue",
                text = "Continue",
                bounds = DeviceBounds(0, 0, 100, 80),
                clickable = true,
                longClickable = false,
                scrollable = false,
                editable = false,
                password = false,
                sensitive = false,
                enabled = true,
            ),
        ),
    )

    private val request = DeviceAgentModelRequest(
        task = "Tap Continue",
        targetPackage = snapshot.packageName,
        snapshot = snapshot,
        tools = DeviceActionCatalog.definitions,
    )

    @Test
    fun encodingUsesProtocolSpecificToolsAndNoProviderState() {
        for (protocol in AiProtocol.entries) {
            val root = JSONObject(DeviceAgentProtocolCodec.encode(request, "fixture", protocol))
            assertFalse(root.optBoolean("store", false))
            assertFalse(root.getBoolean("stream"))
            val tools = root.getJSONArray("tools")
            assertEquals(DeviceActionCatalog.definitions.size, tools.length())
            assertTrue(root.toString().contains("Tap Continue"))
            assertFalse(root.toString().contains("api_key"))
            if (protocol == AiProtocol.ANTHROPIC_MESSAGES) {
                assertTrue(tools.getJSONObject(0).has("input_schema"))
            } else if (protocol == AiProtocol.CHAT_COMPLETIONS) {
                assertTrue(tools.getJSONObject(0).has("function"))
            } else {
                assertEquals("function", tools.getJSONObject(0).getString("type"))
                assertTrue(tools.getJSONObject(0).has("name"))
            }
        }
    }

    @Test
    fun eachProtocolDecodesOneBoundedToolCall() {
        val values = listOf(
            AiProtocol.CHAT_COMPLETIONS to """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"click","arguments":"{\"element_ref\":\"0.0\"}"}}]}}]}
            """.trimIndent(),
            AiProtocol.RESPONSES to """
                {"status":"completed","output":[{"type":"function_call","call_id":"call-1","name":"click","arguments":"{\"element_ref\":\"0.0\"}"}]}
            """.trimIndent(),
            AiProtocol.ANTHROPIC_MESSAGES to """
                {"type":"message","role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"call-1","name":"click","input":{"element_ref":"0.0"}}]}
            """.trimIndent(),
        )
        values.forEach { (protocol, raw) ->
            val response = DeviceAgentProtocolCodec.decode(raw, protocol)
            assertFalse(response.completed)
            assertEquals("click", response.calls.single().toolName)
            assertEquals("0.0", response.calls.single().arguments["element_ref"])
        }
    }

    @Test
    fun eachProtocolEncodesAndDecodesAStage6ToolCall() {
        val tools = DeviceActionCatalog.definitions + DeviceStage6ToolCatalog.definitions
        val stage6Request = DeviceAgentModelRequest(
            task = "Read the selected contact",
            targetPackage = snapshot.packageName,
            snapshot = snapshot,
            tools = tools,
        )
        val responses = listOf(
            AiProtocol.CHAT_COMPLETIONS to """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"stage6-1","type":"function","function":{"name":"read_contact","arguments":"{\"display_name\":\"Alice\"}"}}]}}]}""",
            AiProtocol.RESPONSES to """{"status":"completed","output":[{"type":"function_call","call_id":"stage6-1","name":"read_contact","arguments":"{\"display_name\":\"Alice\"}"}]}""",
            AiProtocol.ANTHROPIC_MESSAGES to """{"type":"message","role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"stage6-1","name":"read_contact","input":{"display_name":"Alice"}}]}""",
        )

        responses.forEach { (protocol, raw) ->
            val encoded = DeviceAgentProtocolCodec.encode(stage6Request, "fixture", protocol)
            assertTrue(encoded.contains("read_contact"))
            assertTrue(encoded.contains("CONTACTS"))
            val decoded = DeviceAgentProtocolCodec.decode(raw, protocol)
            assertEquals("read_contact", decoded.calls.single().toolName)
            assertEquals("Alice", decoded.calls.single().arguments["display_name"])
        }
    }

    @Test
    fun textOnlyModelResponseCompletesWithoutADeviceAction() {
        val response = DeviceAgentProtocolCodec.decode(
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Done","tool_calls":[]}}]}""",
            AiProtocol.CHAT_COMPLETIONS,
        )
        assertTrue(response.completed)
        assertEquals("Done", response.message)
    }

    @Test
    fun unknownToolsDuplicateIdsMalformedJsonAndMissingArgumentsAreRejected() {
        val cases = listOf(
            AiProtocol.CHAT_COMPLETIONS to """{"choices":[{"message":{"tool_calls":[{"id":"x","function":{"name":"delete_everything","arguments":"{}"}}]}}]}""",
            AiProtocol.RESPONSES to """{"output":[{"type":"function_call","call_id":"x","name":"click","arguments":"{\"element_ref\":\"0.0\"}"},{"type":"function_call","call_id":"x","name":"click","arguments":"{\"element_ref\":\"0.0\"}"}]}""",
            AiProtocol.ANTHROPIC_MESSAGES to """{"content":[{"type":"tool_use","id":"x","name":"click","input":{"element_ref":1}}]}""",
            AiProtocol.CHAT_COMPLETIONS to """{"choices":[{"message":{"tool_calls":[{"id":"x","function":{"name":"click","arguments":"not-json"}}]}}]}""",
            AiProtocol.RESPONSES to """{"output":[{"type":"function_call","call_id":"x","name":"click","arguments":"{}"}]}""",
        )
        cases.forEach { (protocol, raw) ->
            assertThrows(AiProviderError.Response::class.java) { DeviceAgentProtocolCodec.decode(raw, protocol) }
        }
    }

    @Test
    fun screenshotBytesAreEncodedOnlyWhenTaskSnapshotContainsThem() {
        val snapshotWithPixels = snapshot.copy(screenshotJpeg = byteArrayOf(1, 2, 3, 4))
        val requestWithPixels = request.copy(snapshot = snapshotWithPixels)
        AiProtocol.entries.forEach { protocol ->
            val encoded = DeviceAgentProtocolCodec.encode(requestWithPixels, "fixture", protocol)
            assertTrue(encoded.contains("screenshot_available"))
            assertTrue(encoded.contains("AQIDBA"))
        }
        val withoutPixels = DeviceAgentProtocolCodec.encode(request, "fixture", AiProtocol.CHAT_COMPLETIONS)
        assertFalse(withoutPixels.contains("image_url"))
    }
}
