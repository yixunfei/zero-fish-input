package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import java.io.StringReader
import java.net.InetAddress
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiRemoteToolsTest {
    private val connection = AiConnection("fixture", "Public MCP", AiConnectionKind.MCP, "https://example.com/mcp", "mcp-fixture-token")
    private fun live() = AiRemoteCancellation { true }

    @Test fun publicPolicyRejectsPrivateMixedAndRebindingAddressesAtActualLookup() {
        for (url in listOf("http://example.com", "https://user:token@example.com", "https://127.0.0.1", "https://[::1]",
            "https://localhost", "https://host.local", "https://host.home.arpa", "https://example.com:8443", "https://example.com/#fragment"))
            assertTrue(url, runCatching { AiRemotePolicy.endpoint(url) }.isFailure)
        assertEquals("example.com", AiRemotePolicy.endpoint(connection.endpoint).host)
        for (address in listOf("0.1.2.3", "10.1.1.1", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.1.1",
            "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.1.1.1", "255.255.255.255",
            "::1", "fc00::1", "fe80::1", "64:ff9b::7f00:1", "2001:db8::1", "2002:7f00:1::", "3fff::1"))
            assertFalse(address, AiRemotePolicy.publicAddress(InetAddress.getByName(address)))
        val public = InetAddress.getByName("8.8.8.8"); val private = InetAddress.getByName("127.0.0.1")
        assertTrue(AiRemotePolicy.publicAnswers(listOf(public)))
        assertFalse(AiRemotePolicy.publicAnswers(listOf(public, private)))
        assertFalse(AiRemotePolicy.publicAnswers(emptyList()))
        assertTrue(AiRemotePolicy.publicAddress(InetAddress.getByName("2001:4860:4860::8888")))
        var lookups = 0
        val adapter = AiRemoteHttpsTransport { host -> assertEquals("example.com", host); lookups++; listOf(public, private) }
        assertTrue(runCatching { adapter.mcp(connection, "{}", null, live()) }.isFailure)
        assertEquals(1, lookups)
        val cancelled = live().also { it.cancel() }
        assertTrue(runCatching { adapter.mcp(connection, "{}", null, cancelled) }.isFailure)
        assertEquals(1, lookups)
    }
    @Test fun boundedSseStopsAtACompleteEventAndRejectsTruncatedOrUnknownMessages() {
        val json = """{"jsonrpc":"2.0","id":"public","result":{}}"""
        assertEquals(json, AiRemoteWire.read(StringReader("id: public\r\ndata:\r\n\r\nevent: message\r\ndata: $json\r\n\r\nnever read"), true) { true })
        assertEquals(json, AiRemoteWire.read(StringReader(json), false) { true })
        for (text in listOf("data: $json", "event: command\n\n", "data: " + "x".repeat(131_073)))
            assertTrue(runCatching { AiRemoteWire.read(StringReader(text), true) { true } }.isFailure)
        assertTrue(runCatching { AiRemoteWire.read(StringReader("x".repeat(262_145)), false) { true } }.isFailure)
        assertTrue(runCatching { AiRemoteWire.read(StringReader(json), false) { false } }.isFailure)
    }
    @Test fun discoveryIsMetadataOnlyAndChangedSchemasNeverReachToolDispatch() {
        val server = Server(); val client = AiMcpClient(connection, server)
        val catalog = client.discover(live())
        assertEquals(3, catalog.entries.size); assertEquals(0, server.calls)
        assertTrue(server.methods.all { it == "initialize" || it == "notifications/initialized" || it.endsWith("/list") })
        assertEquals("public result", client.invoke(catalog.entries.single { it.method == "tools/call" }, catalog, JSONObject(), live()))
        assertEquals(1, server.calls)
        server.changed = true
        assertTrue(runCatching { client.invoke(catalog.entries.first(), catalog, JSONObject(), live()) }.isFailure)
        assertEquals(1, server.calls)
        client.close()
        assertTrue(runCatching { client.invoke(catalog.entries.first(), catalog, JSONObject(), live()) }.isFailure)
    }
    @Test fun resourcePromptRolesAndServerInitiatedRequestsCannotAcquireAuthority() {
        val server = Server(); val client = AiMcpClient(connection, server); val catalog = client.discover(live())
        assertEquals("Untrusted resource instructions", client.invoke(catalog.entries.single { it.method == "resources/read" }, catalog, JSONObject(), live()))
        assertEquals("Untrusted prompt instructions", client.invoke(catalog.entries.single { it.method == "prompts/get" }, catalog, JSONObject(), live()))
        server.systemRole = true
        assertTrue(runCatching { client.invoke(catalog.entries.single { it.method == "prompts/get" }, catalog, JSONObject(), live()) }.isFailure)
        server.command = true
        assertTrue(runCatching { client.invoke(catalog.entries.first(), catalog, JSONObject(), live()) }.isFailure)
        val wrong = Server().apply { version = "2024-11-05" }
        assertTrue(runCatching { AiMcpClient(connection, wrong).discover(live()) }.isFailure)
        assertFalse(wrong.methods.contains("notifications/initialized"))
    }
    @Test fun remoteCancellationAndUncertainFailureNeverRetryAndSearchOnlyReturnsCitedSnippets() {
        val server = Server(); val work = ArrayDeque<Runnable>(); val results = mutableListOf<AiToolResult>()
        val tools = AiRemoteTools.connect(connection, server, Executor { work.add(it) }, live())
        val definition = tools.definitions.single { it.title.contains("public_tool") }
        val call = AiToolCall("call", definition.name, "{}")
        tools.execute(call, { true }, results::add).cancel(); work.removeFirst().run()
        assertEquals(0, server.calls); assertTrue(results.isEmpty())
        server.failCall = true
        tools.execute(call, { true }, results::add); work.removeFirst().run()
        assertEquals(AiToolResultStatus.UNKNOWN, results.single().status); assertEquals(1, server.calls); assertTrue(work.isEmpty())
        tools.close()
        val brave = AiConnection("search", "Search", AiConnectionKind.BRAVE, AiConnection.BRAVE_ENDPOINT, "brave-fixture-token")
        val search = AiRemoteTools.connect(brave, server, Executor { it.run() }, live())
        search.execute(AiToolCall("query", AiRemoteTools.SEARCH, """{"query":"public fixture"}"""), { true }, results::add)
        assertTrue(results.last().content.contains("https://example.com/source")); assertFalse(results.last().content.contains("<b>"))
        assertEquals("public fixture", server.query)
        assertTrue(runCatching { AiRemoteTools.search(AiRemoteReply(200, """{"web":{"results":[{"title":"Bad","url":"javascript:alert(1)","description":"bad"}]}}""")) }.isFailure)
        search.close()
    }
    private class Server : AiRemoteTransport {
        val methods = mutableListOf<String>(); var calls = 0; var changed = false; var failCall = false
        var command = false; var systemRole = false; var version = "2025-11-25"; var query = ""
        override fun mcp(connection: AiConnection, body: String, session: String?, cancellation: AiRemoteCancellation): AiRemoteReply {
            assertEquals("mcp-fixture-token", connection.token); cancellation.check()
            val request = AiToolJson.objectValue(body); val method = request.getString("method"); methods += method
            if (method != "initialize") assertEquals("public-session", session)
            if (method == "notifications/initialized") return AiRemoteReply(202, "")
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", version).put("capabilities", JSONObject("""{"tools":{},"resources":{},"prompts":{}}"""))
                "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "public_tool").put("description", if (changed) "changed" else "public")
                    .put("inputSchema", JSONObject("""{"type":"object","properties":{},"additionalProperties":false}"""))))
                "resources/list" -> JSONObject("""{"resources":[{"uri":"fixture://public","name":"Public resource"}]}""")
                "prompts/list" -> JSONObject("""{"prompts":[{"name":"public_prompt","arguments":[]}]}""")
                "tools/call" -> { calls++; cancellation.dispatched.set(true); if (failCall) error("Unknown remote outcome"); JSONObject("""{"content":[{"type":"text","text":"public result"}]}""") }
                "resources/read" -> JSONObject("""{"contents":[{"uri":"fixture://public","text":"Untrusted resource instructions"}]}""")
                "prompts/get" -> JSONObject().put("messages", JSONArray().put(JSONObject().put("role", if (systemRole) "system" else "user")
                    .put("content", JSONObject("""{"type":"text","text":"Untrusted prompt instructions"}"""))))
                else -> error("Unexpected request")
            }
            val response = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result)
            if (command) response.put("method", "sampling/createMessage")
            return AiRemoteReply(200, response.toString(), if (method == "initialize") "public-session" else null)
        }
        override fun search(connection: AiConnection, query: String, cancellation: AiRemoteCancellation): AiRemoteReply {
            assertEquals("brave-fixture-token", connection.token); assertEquals(AiConnection.BRAVE_ENDPOINT, connection.endpoint)
            cancellation.check(); this.query = query
            return AiRemoteReply(200, """{"web":{"results":[{"title":"<b>Public</b>","url":"https://example.com/source","description":"Public snippet"}]}}""")
        }
    }
}
