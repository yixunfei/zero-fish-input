package dev.zeroinput.ai.api

import java.net.URI

/** API base or matching full generation address; resolution never changes the origin. */
class AiEndpoint private constructor(val generation: URI, val models: URI) {
    companion object {
        fun parse(raw: String, protocol: AiProtocol = AiProtocol.CHAT_COMPLETIONS): AiEndpoint {
            try {
                require(raw.length in 1..512 && raw.none {
                    it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                })
                val uri = URI(raw.trim())
                require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
                require(uri.userInfo == null && uri.query == null && uri.fragment == null)
                require(uri.port in -1..65535 && uri.port != 0)
                require(uri.path.split('/').none { it == "." || it == ".." })
                require(uri.path.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
                require(!uri.rawPath.orEmpty().contains('%') && !uri.path.contains("//"))
                val path = uri.rawPath.orEmpty().trimEnd('/')
                val fullRoute = AiProtocol.entries.firstOrNull { path.endsWith("/${it.route}") }
                require(fullRoute == null || fullRoute == protocol)
                require(!path.endsWith("/models"))
                val base = when {
                    path.isEmpty() -> "/v1"
                    fullRoute != null -> path.removeSuffix("/${protocol.route}")
                    else -> path
                }
                val origin = "https://${uri.rawAuthority}"
                return AiEndpoint(URI("$origin$base/${protocol.route}"), URI("$origin$base/models"))
            } catch (_: Exception) {
                throw AiProviderError.Configuration("AI endpoint is invalid")
            }
        }
    }
}
