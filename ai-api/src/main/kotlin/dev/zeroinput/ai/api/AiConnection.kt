package dev.zeroinput.ai.api

import java.net.URI
import java.util.Collections

enum class AiConnectionKind { MCP, BRAVE }

/** Explicit endpoint credential. Never shared with the model configuration. */
class AiConnection(val id: String, val name: String, val kind: AiConnectionKind,
    val endpoint: String, val token: String) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(name.isNotBlank() && name.length <= 64 && name.none(Char::isISOControl))
        require(endpoint.length in 1..2_048 && endpoint.none(Char::isISOControl))
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(token.length <= 4_096 && token.all { it.code in 33..126 })
        if (kind == AiConnectionKind.BRAVE) require(endpoint == BRAVE_ENDPOINT && token.isNotBlank())
    }
    override fun toString() = "AiConnection(redacted)"
    companion object { const val BRAVE_ENDPOINT = "https://api.search.brave.com/res/v1/web/search" }
}

class AiConnectionLibrary(val revision: Long = 0, connections: List<AiConnection> = emptyList()) {
    val connections: List<AiConnection> = Collections.unmodifiableList(connections.toList())
    init { require(revision >= 0 && connections.size <= 8 && connections.map { it.id }.distinct().size == connections.size) }
    override fun toString() = "AiConnectionLibrary(redacted)"
}
