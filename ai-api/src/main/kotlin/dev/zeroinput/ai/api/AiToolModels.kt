package dev.zeroinput.ai.api

import java.util.Collections

enum class AiToolOrigin { FILE, MCP, SEARCH }
enum class AiToolEffect { READ, WRITE, REMOTE }

/** The app owns identity/effect. A server's annotations never grant authority. */
class AiToolDefinition(val id: String, val name: String, val title: String, val description: String,
    val schema: String, val origin: AiToolOrigin, val effect: AiToolEffect) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_./-]{1,96}")) && name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(title.isNotBlank() && title.length <= 160 && description.length <= 1_024 && schema.length in 2..16_384)
        require(listOf(title, description).all { it.none { c -> c.isISOControl() && c !in "\n\r\t" } })
    }
    override fun toString() = "AiToolDefinition(redacted)"
}

class AiToolCall(val id: String, val name: String, val arguments: String) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(arguments.length in 2..32_768)
    }
    override fun toString() = "AiToolCall(redacted)"
}

enum class AiToolResultStatus { SUCCESS, REJECTED, FAILED, UNKNOWN }
class AiToolResult(val callId: String, val status: AiToolResultStatus, val content: String) {
    init { require(callId.length in 1..128 && content.length <= 4_096 &&
        content.none { it.isISOControl() && it !in "\n\r\t" }) }
    override fun toString() = "AiToolResult(status=$status)"
}
class AiToolContinuation(val id: String, val opaque: String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && opaque.length in 1..16_384) }
    override fun toString() = "AiToolContinuation(redacted)"
}
class AiToolTurn(val call: AiToolCall, val result: AiToolResult, continuation: List<AiToolContinuation> = emptyList()) {
    val continuation: List<AiToolContinuation> = Collections.unmodifiableList(continuation.toList())
    init { require(call.id == result.callId && continuation.size <= 4) }
    override fun toString() = "AiToolTurn(redacted)"
}

class AiToolRequest(val input: String, tools: List<AiToolDefinition>,
    val workspace: AiWorkspaceContext = AiWorkspaceContext(), turns: List<AiToolTurn> = emptyList(), val model: String? = null) {
    val tools: List<AiToolDefinition> = Collections.unmodifiableList(tools.toList())
    val turns: List<AiToolTurn> = Collections.unmodifiableList(turns.toList())
    init {
        require(input.isNotBlank() && input.length <= 8_192 && tools.size in 1..32 && turns.size <= 40)
        require(input.none { it.isISOControl() && it !in "\n\r\t" })
        require(tools.map { it.id }.distinct().size == tools.size && tools.map { it.name }.distinct().size == tools.size)
        require(turns.map { it.call.id }.distinct().size == turns.size && turns.all { turn -> tools.any { it.name == turn.call.name } })
        workspace.validate(emptyList(), emptyList(), tools.map { it.id }.toSet())
    }
    override fun toString() = "AiToolRequest(redacted)"
}
class AiToolResponse(val message: String, val call: AiToolCall? = null, continuation: List<AiToolContinuation> = emptyList()) {
    val continuation: List<AiToolContinuation> = Collections.unmodifiableList(continuation.toList())
    init { require(message.length <= 8_192 && (message.isNotBlank() || call != null) && continuation.size <= 4 &&
        message.none { it.isISOControl() && it !in "\n\r\t" }) }
    override fun toString() = "AiToolResponse(redacted)"
}
interface AiToolProvider {
    fun request(request: AiToolRequest, completed: (Result<AiToolResponse>) -> Unit): AiRequestHandle
}

/** Executors must check current immediately before every external dispatch. */
interface AiToolExecutor {
    fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit): AiRequestHandle
}
