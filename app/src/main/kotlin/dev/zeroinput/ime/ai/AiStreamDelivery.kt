package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiStreamEvent

/** One pending UI callback, bounded text and terminal-event deduplication. */
internal class AiStreamDelivery(
    private val post: (() -> Unit) -> Boolean,
    private val deliver: (AiStreamEvent) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val delta = StringBuilder()
    private var terminal: AiStreamEvent? = null
    private var posted = false
    private var ended = false
    private var closed = false

    fun offer(event: AiStreamEvent) {
        val schedule = synchronized(lock) {
            if (closed || ended) return
            when (event) {
                AiStreamEvent.Started -> return
                is AiStreamEvent.Delta -> {
                    val remaining = AiLimits.MAX_OUTPUT_CHARS - delta.length
                    if (event.text.length > remaining) {
                        terminal = AiStreamEvent.Failed(dev.zeroinput.ai.api.AiProviderError.Response("AI output exceeded limit"))
                        ended = true
                    } else delta.append(event.text)
                }
                else -> { terminal = event; ended = true }
            }
            if (posted) false else { posted = true; true }
        }
        if (schedule) {
            val posted = runCatching { post(::drain) }.getOrDefault(false)
            if (!posted) close()
        }
    }

    private fun drain() {
        val events = synchronized(lock) {
            posted = false
            if (closed) return
            buildList {
                if (delta.isNotEmpty()) add(AiStreamEvent.Delta(delta.toString()))
                terminal?.let(::add)
            }.also { delta.setLength(0); terminal = null }
        }
        events.forEach(deliver)
    }

    override fun close() = synchronized(lock) {
        closed = true
        delta.setLength(0)
        terminal = null
    }
}
