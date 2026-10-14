package dev.zeroinput.ai.api

/** Explicit wire contract; never selected by probing a server with user content. */
enum class AiProtocol(val route: String, val supportsAudio: Boolean) {
    CHAT_COMPLETIONS("chat/completions", true),
    RESPONSES("responses", false),
    ANTHROPIC_MESSAGES("messages", false),
}
