package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class AiMcpEntry(val definition: AiToolDefinition, val method: String, val target: String, val fingerprint: String) {
    override fun toString() = "AiMcpEntry(redacted)"
}
internal class AiMcpCatalog(entries: List<AiMcpEntry>, val unsupported: Int, val fingerprint: String) {
    val entries = entries.toList()
    override fun toString() = "AiMcpCatalog(redacted)"
}

/** Serial, task-owned Streamable HTTP subset. Returned roles/content never become system messages. */
internal class AiMcpClient(private val connection: AiConnection, private val transport: AiRemoteTransport) : AutoCloseable {
    private var session: String? = null
    private var initialized = false
    private var capabilities = emptySet<String>()
    private val closed = AtomicBoolean()
    init { require(connection.kind == AiConnectionKind.MCP); AiRemotePolicy.endpoint(connection.endpoint) }

    fun discover(cancel: AiRemoteCancellation): AiMcpCatalog {
        check(!closed.get() && !initialized)
        val result = rpc("initialize", JSONObject().put("protocolVersion", VERSION).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "ZeroInput").put("version", "1")), cancel)
        require(result.opt("protocolVersion") == VERSION)
        val offered = result.getJSONObject("capabilities")
        capabilities = setOf("tools", "resources", "prompts").filter { offered.has(it) }.toSet()
        capabilities.forEach { offered.getJSONObject(it) }
        val reply = transport.mcp(connection, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(), session, cancel)
        require(reply.status == 202 && reply.body.isEmpty()); checkSession(reply.session)
        initialized = true
        return catalog(cancel)
    }
    fun invoke(entry: AiMcpEntry, reviewed: AiMcpCatalog, arguments: JSONObject, cancel: AiRemoteCancellation): String {
        check(initialized && !closed.get())
        // Server-side list changes cannot silently substitute a newly defined operation after approval.
        require(catalog(cancel).fingerprint == reviewed.fingerprint)
        check(!closed.get()); cancel.check()
        val params = when (entry.method) {
            "tools/call", "prompts/get" -> JSONObject().put("name", entry.target).put("arguments", arguments)
            "resources/read" -> JSONObject().put("uri", entry.target)
            else -> error("Unsupported operation")
        }
        val result = rpc(entry.method, params, cancel)
        if (entry.method == "tools/call") {
            require(!result.has("isError") || result.opt("isError") == false)
            return textContent(result.getJSONArray("content"))
        }
        if (entry.method == "resources/read") {
            val contents = result.getJSONArray("contents"); require(contents.length() in 1..8)
            return bounded((0 until contents.length()).map { index ->
                val item = contents.getJSONObject(index)
                require(item.opt("uri") == entry.target && !item.has("blob"))
                item.get("text") as? String ?: error("Text resource required")
            })
        }
        val messages = result.getJSONArray("messages"); require(messages.length() in 1..8)
        return bounded((0 until messages.length()).map { index ->
            val message = messages.getJSONObject(index)
            require(message.opt("role") in setOf("user", "assistant"))
            textContent(JSONArray().put(message.getJSONObject("content")))
        })
    }
    private fun catalog(cancel: AiRemoteCancellation): AiMcpCatalog {
        val entries = mutableListOf<AiMcpEntry>(); val fingerprints = mutableListOf<String>(); var unsupported = 0
        for (kind in capabilities.sorted()) {
            for (raw in listed(kind, cancel)) {
                fingerprints += hash(kind + canonical(raw))
                val entry = runCatching { entry(kind, raw) }.getOrNull()
                if (entry == null) unsupported++ else entries += entry
            }
        }
        require(entries.map { it.definition.name }.distinct().size == entries.size)
        // Unsupported descriptors still participate in the digest so changes revoke a selection.
        return AiMcpCatalog(entries, unsupported, hash(fingerprints.sorted().joinToString("|")))
    }
    private fun listed(kind: String, cancel: AiRemoteCancellation): List<JSONObject> {
        val result = mutableListOf<JSONObject>(); val cursors = mutableSetOf<String>(); var cursor: String? = null
        repeat(4) {
            val page = rpc("$kind/list", JSONObject().also { if (cursor != null) it.put("cursor", cursor) }, cancel)
            val items = page.getJSONArray(kind); require(result.size + items.length() <= 32)
            for (index in 0 until items.length()) result += items.getJSONObject(index)
            if (!page.has("nextCursor")) return result
            val next = page.get("nextCursor") as? String ?: error("Invalid cursor")
            require(next.length in 1..512 && cursors.add(next)); cursor = next
        }
        error("Catalog limit")
    }
    private fun entry(kind: String, raw: JSONObject): AiMcpEntry {
        val target = raw.get(if (kind == "resources") "uri" else "name") as? String ?: error("Invalid target")
        require(target.length in 1..(if (kind == "resources") 512 else 128) && target.none(Char::isISOControl))
        val label = (raw.opt("title") ?: raw.opt("name") ?: target) as? String ?: error("Invalid title")
        require(label.isNotBlank() && label.length <= 160)
        val description = raw.opt("description")?.let { it as? String ?: error("Invalid description") } ?: ""
        require(description.length <= 1_024)
        val schema = when (kind) {
            "tools" -> AiToolJson.schema(raw.getJSONObject("inputSchema").toString())
            "resources" -> JSONObject(EMPTY_SCHEMA)
            "prompts" -> promptSchema(raw)
            else -> error("Unsupported kind")
        }
        val key = hash("${connection.id}/$kind/$target").take(32)
        val method = when (kind) { "tools" -> "tools/call"; "resources" -> "resources/read"; else -> "prompts/get" }
        val definition = AiToolDefinition("mcp/$key", "mcp_$key", "${connection.name}: $label".take(160),
            "Source: ${AiRemotePolicy.endpoint(connection.endpoint).host}\nOperation: $method\nTarget: $target\n$description".take(1_024),
            schema.toString(), AiToolOrigin.MCP, AiToolEffect.REMOTE)
        return AiMcpEntry(definition, method, target, hash(canonical(raw)))
    }
    private fun promptSchema(raw: JSONObject): JSONObject {
        val schema = JSONObject(EMPTY_SCHEMA); val properties = JSONObject(); val required = JSONArray()
        val args = raw.optJSONArray("arguments") ?: JSONArray().also { require(!raw.has("arguments")) }
        require(args.length() <= 16)
        for (i in 0 until args.length()) {
            val arg = args.getJSONObject(i); val name = arg.get("name") as? String ?: error("Invalid argument")
            require(name.length in 1..64 && !properties.has(name))
            val field = JSONObject().put("type", "string").put("maxLength", 2_048)
            if (arg.has("description")) field.put("description", arg.get("description") as? String ?: error("Invalid description"))
            properties.put(name, field)
            if (arg.has("required")) { require(arg.get("required") is Boolean); if (arg.getBoolean("required")) required.put(name) }
        }
        return schema.put("properties", properties).put("required", required).also { AiToolJson.schema(it.toString()) }
    }
    private fun rpc(method: String, params: JSONObject, cancel: AiRemoteCancellation): JSONObject {
        check(!closed.get()); cancel.check()
        val id = UUID.randomUUID().toString()
        val request = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val reply = transport.mcp(connection, request.toString(), session, cancel)
        require(reply.status == 200); checkSession(reply.session)
        if (!initialized && method == "initialize") session = reply.session
        val root = AiToolJson.objectValue(reply.body, 262_144)
        require(root.opt("jsonrpc") == "2.0" && root.opt("id") == id && !root.has("method") && !root.has("error"))
        require(root.keys().asSequence().all { it in setOf("jsonrpc", "id", "result") })
        check(!closed.get()); cancel.check(); return root.getJSONObject("result")
    }
    private fun checkSession(value: String?) {
        if (value != null) {
            require(value.length in 1..128 && value.all { it.code in 33..126 })
            if (initialized || session != null) require(value == session)
        }
    }
    private fun textContent(items: JSONArray): String {
        require(items.length() in 1..16)
        return bounded((0 until items.length()).map { index ->
            val item = items.getJSONObject(index); require(item.opt("type") == "text")
            item.get("text") as? String ?: error("Text content required")
        })
    }
    private fun bounded(parts: List<String>): String = parts.joinToString("\n\n").also {
        require(it.length <= 3_800 && it.none { c -> c.isISOControl() && c !in "\n\r\t" })
    }
    override fun close() { closed.set(true); session = null; capabilities = emptySet() }
    private companion object {
        const val VERSION = "2025-11-25"
        const val EMPTY_SCHEMA = """{"type":"object","properties":{},"additionalProperties":false}"""
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun canonical(value: Any): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}
