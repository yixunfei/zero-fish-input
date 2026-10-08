package dev.zeroinput.ai.api

import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class AiAction(val promptInstruction: String) {
    ASK("回答用户的问题"),
    PLAN("把目标拆解成可执行的步骤"),
    POLISH("在不改变原意的前提下润色文字"),
    REWRITE("改写文字，使表达更清晰自然"),
    TRANSLATE("翻译文字"),
}

enum class AiRole { SYSTEM, USER, ASSISTANT }

data class AiMessage(
    val role: AiRole,
    val content: String,
) {
    init {
        require(content.isNotBlank()) { "AI message must not be blank" }
        require(content.length <= AiLimits.MAX_MESSAGE_CHARS) { "AI message is too large" }
    }
}

data class AiConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<AiMessage> = emptyList(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(id.isNotBlank() && id.length <= AiLimits.MAX_ID_CHARS) { "Invalid AI conversation id" }
        require(title.isNotBlank() && title.length <= AiLimits.MAX_TITLE_CHARS) { "Invalid AI conversation title" }
        require(messages.size <= AiLimits.MAX_HISTORY_MESSAGES) { "AI history is too large" }
    }
}

/** Metadata shown before a user explicitly opens a saved conversation. */
data class AiConversationSummary(
    val id: String,
    val title: String,
    val updatedAtEpochMillis: Long,
)

data class AiRequest(
    val conversationId: String?,
    val action: AiAction,
    val input: String,
    val targetLanguage: String? = null,
    val history: List<AiMessage> = emptyList(),
    val attachments: List<AiAttachment> = emptyList(),
    val outputTokenLimit: Int? = null,
    /** Optional workbench choice, restricted to the bound provider's saved model list. */
    val model: String? = null,
    val references: List<AiReference> = emptyList(),
) {
    init {
        require(input.isNotBlank()) { "AI input must not be blank" }
        require(input.length <= AiLimits.MAX_INPUT_CHARS) { "AI input is too large" }
        require(outputTokenLimit == null || outputTokenLimit in 1..4096) { "Invalid output limit" }
        require(model == null || model.isNotBlank() && model.length <= 128 && model.none(Char::isISOControl)) {
            "Invalid AI model"
        }
        require(history.size <= AiLimits.MAX_HISTORY_MESSAGES) { "AI history is too large" }
        AiReference.validate(history, references)
        require(targetLanguage == null || targetLanguage.length in 2..32) { "Invalid target language" }
        require(attachments.size <= AiLimits.MAX_ATTACHMENTS) { "Too many AI attachments" }
        require(attachments.sumOf { it.bytes.size } <= AiLimits.MAX_ATTACHMENT_BYTES) { "AI attachments are too large" }
    }
}

/** Transient content; draft owners retain their bytes and submit independent request copies. */
class AiAttachment(val mimeType: String, val bytes: ByteArray, val displayName: String) {
    init {
        require(mimeType in setOf("text/plain", "image/jpeg", "image/png", "image/webp",
            "audio/wav", "audio/mpeg"))
        require(bytes.isNotEmpty() && bytes.size <= AiLimits.MAX_ATTACHMENT_BYTES)
        require(displayName.length in 1..128 && displayName.none(Char::isISOControl))
        if (mimeType == "text/plain") {
            val decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            try {
                require(decoded.remaining() <= AiLimits.MAX_INPUT_CHARS)
                require(decoded.none { it.isISOControl() && it !in "\n\r\t" })
            } finally {
                if (decoded.hasArray()) decoded.array().fill('\u0000')
            }
        }
    }

    override fun toString(): String = "AiAttachment(redacted)"
}

sealed interface AiStreamEvent {
    data object Started : AiStreamEvent
    data class Delta(val text: String) : AiStreamEvent
    data class Completed(val text: String) : AiStreamEvent
    data class Failed(val error: AiProviderError) : AiStreamEvent
    data object Cancelled : AiStreamEvent
}

interface AiRequestHandle {
    fun cancel()
}

interface AiProvider {
    /** Owns request attachment bytes from entry, including rejection, cancellation and failure. */
    fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle
}

sealed class AiProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Configuration(message: String) : AiProviderError(message)
    class Network(message: String, cause: Throwable? = null,
        val reason: AiNetworkFailure = AiNetworkFailure.CONNECTION) : AiProviderError(message, cause)
    class Response(message: String) : AiProviderError(message)
    class Policy(message: String) : AiProviderError(message)
    class Storage : AiProviderError("AI conversation storage operation failed")
}

enum class AiNetworkFailure { CONNECTION, TIMEOUT, AUTHENTICATION, MODEL_OR_ENDPOINT, RATE_LIMIT, SERVICE }

data class AiGenerationPolicy(
    val enabled: Boolean,
    val networkAllowed: Boolean,
    val sensitiveEditor: Boolean,
    val personalizationAllowed: Boolean = true,
    val suggestionsAllowed: Boolean = true,
) {
    fun check(request: AiRequest) {
        if (!enabled || !networkAllowed) throw AiProviderError.Policy("AI is disabled")
        if (sensitiveEditor) throw AiProviderError.Policy("AI is unavailable in sensitive input")
        if (!personalizationAllowed || !suggestionsAllowed) {
            throw AiProviderError.Policy("AI is unavailable in this input")
        }
        require(request.input.length <= AiLimits.MAX_INPUT_CHARS) { "AI input is too large" }
    }
}

object AiLimits {
    const val MAX_ID_CHARS = 128
    const val MAX_TITLE_CHARS = 128
    const val MAX_MESSAGE_CHARS = 32_768
    const val MAX_INPUT_CHARS = 16_384
    const val MAX_OUTPUT_CHARS = 32_768
    const val MAX_STREAM_LINE_CHARS = 16_384
    const val MAX_STREAM_CHARS = 2 * 1024 * 1024
    const val MAX_HISTORY_MESSAGES = 20
    const val MAX_CONVERSATIONS = 32
    const val MAX_TIMEOUT_MS = 120_000L
    const val MAX_ATTACHMENTS = 2
    const val MAX_ATTACHMENT_BYTES = 1_000_000
}
