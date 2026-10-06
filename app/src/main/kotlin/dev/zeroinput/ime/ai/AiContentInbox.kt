package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAttachment
import dev.zeroinput.ai.api.AiLimits

/** One expiring handoff, claimed only by an explicit tap in an eligible keyboard session. */
internal class AiContentInbox(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var pending: AiImportedContent? = null
    private var expiresAt = 0L

    @Synchronized fun put(content: AiImportedContent) {
        clear()
        pending = content
        expiresAt = now() + LIFETIME_MS
    }

    @Synchronized fun available(): Boolean {
        expire()
        return pending != null
    }

    @Synchronized fun take(): AiImportedContent? {
        expire()
        return pending.also { pending = null }
    }

    @Synchronized fun expire() {
        if (now() >= expiresAt) clear()
    }

    @Synchronized fun clear() {
        pending?.close()
        pending = null
    }

    companion object { const val LIFETIME_MS = 120_000L }
}

internal class AiImportedContent(val text: CharArray, val attachments: List<AiAttachment>) : AutoCloseable {
    init {
        require(text.size <= AiLimits.MAX_INPUT_CHARS)
        require(attachments.size <= AiLimits.MAX_ATTACHMENTS)
        require(attachments.sumOf { it.bytes.size } <= AiLimits.MAX_ATTACHMENT_BYTES)
    }

    override fun close() {
        text.fill('\u0000')
        attachments.forEach { it.bytes.fill(0) }
    }
    override fun toString() = "AiImportedContent(redacted)"
}
