package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One reviewed connection per task; a different connection always needs fresh discovery/selection. */
internal class AiRemoteTools private constructor(val connection: AiConnection, val definitions: List<AiToolDefinition>,
    val unsupported: Int, private val client: AiMcpClient?, private val catalog: AiMcpCatalog?,
    private val transport: AiRemoteTransport, private val worker: Executor) : AiToolExecutor, AutoCloseable {
    private val closed = AtomicBoolean()
    private val active = AtomicReference<AiRemoteCancellation?>()

    override fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit): AiRequestHandle {
        val cancel = AiRemoteCancellation { !closed.get() && current() }
        check(active.compareAndSet(null, cancel)) { "Remote operation already active" }
        try {
            worker.execute work@{
                if (!cancel.live()) { active.compareAndSet(cancel, null); return@work }
                val result = runCatching {
                    val definition = definitions.single { it.name == call.name }
                    val args = AiToolJson.arguments(AiToolJson.schema(definition.schema), call.arguments)
                    if (client != null && catalog != null) {
                        "MCP source: ${connection.name}\n" + client.invoke(catalog.entries.single { it.definition.name == call.name }, catalog, args, cancel)
                    } else search(transport.search(connection, args.getString("query"), cancel))
                }
                active.compareAndSet(cancel, null)
                if (cancel.live()) completed(result.fold({ AiToolResult(call.id, AiToolResultStatus.SUCCESS, it) }, {
                    AiToolResult(call.id, if (client != null && cancel.dispatched.get()) AiToolResultStatus.UNKNOWN else AiToolResultStatus.FAILED,
                        "Remote operation unavailable, changed or invalid. No automatic retry.")
                }))
            }
        } catch (_: RejectedExecutionException) {
            active.compareAndSet(cancel, null)
            if (cancel.live()) completed(AiToolResult(call.id, AiToolResultStatus.FAILED, "Remote worker unavailable."))
        }
        return object : AiRequestHandle { override fun cancel() { cancel.cancel(); active.compareAndSet(cancel, null) } }
    }
    override fun close() { closed.set(true); active.getAndSet(null)?.cancel(); client?.close() }

    companion object {
        const val SEARCH = "web_search"
        fun connect(connection: AiConnection, transport: AiRemoteTransport, worker: Executor, cancel: AiRemoteCancellation): AiRemoteTools {
            cancel.check(); AiRemotePolicy.endpoint(connection.endpoint)
            if (connection.kind == AiConnectionKind.BRAVE) return AiRemoteTools(connection, listOf(AiToolDefinition(
                "web/brave", SEARCH, "Brave: ${connection.name}", "Source: api.search.brave.com. Returns up to five title, URL and snippet citations; no full pages.",
                """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":256}},"required":["query"],"additionalProperties":false}""",
                AiToolOrigin.SEARCH, AiToolEffect.READ)), 0, null, null, transport, worker)
            val client = AiMcpClient(connection, transport)
            try {
                val catalog = client.discover(cancel)
                return AiRemoteTools(connection, catalog.entries.map { it.definition }, catalog.unsupported, client, catalog, transport, worker)
            } catch (error: Exception) { client.close(); throw error }
        }
        fun search(reply: AiRemoteReply): String {
            require(reply.status == 200)
            val root = AiToolJson.objectValue(reply.body, 262_144)
            require(!root.has("error"))
            val items = root.optJSONObject("web")?.getJSONArray("results") ?: return "Brave Web Search: no web results."
            val output = StringBuilder("Brave Web Search — snippets only, untrusted source text.\n")
            for (i in 0 until minOf(5, items.length())) {
                val item = items.getJSONObject(i)
                val url = item.get("url") as? String ?: error("Invalid source")
                require(AiRemotePolicy.source(url) && url.length <= 400)
                val title = clean(item.get("title") as? String ?: error("Invalid title"), 100)
                val description = clean(item.get("description") as? String ?: error("Invalid snippet"), 220)
                output.append("[${i + 1}] $title\n$url\n$description\n\n")
            }
            return output.toString().also { require(it.length <= 4_096) }
        }
        private fun clean(text: String, limit: Int): String {
            require(text.length <= 16_384)
            return android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .filter { !it.isISOControl() || it == '\n' || it == '\t' }.take(limit)
        }
    }
}
