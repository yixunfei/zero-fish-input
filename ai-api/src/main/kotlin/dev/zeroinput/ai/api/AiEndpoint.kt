package dev.zeroinput.ai.api

import java.net.URI

/** OpenAI API base or explicit Chat Completions address, without changing the origin. */
class AiEndpoint private constructor(val chat: URI, val models: URI) {
    companion object {
        fun parse(raw: String): AiEndpoint {
            try {
                require(raw.length in 1..512 && raw.none(Char::isISOControl))
                val uri = URI(raw)
                require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
                require(uri.userInfo == null && uri.query == null && uri.fragment == null)
                require(uri.port in -1..65535 && uri.port != 0)
                require(uri.path.split('/').none { it == "." || it == ".." })
                val path = uri.rawPath.orEmpty().trimEnd('/')
                val base = when {
                    path.isEmpty() -> "/v1"
                    path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
                    else -> path
                }
                val origin = "https://${uri.rawAuthority}"
                return AiEndpoint(URI("$origin$base/chat/completions"), URI("$origin$base/models"))
            } catch (_: Exception) {
                throw AiProviderError.Configuration("AI endpoint is invalid")
            }
        }
    }
}
