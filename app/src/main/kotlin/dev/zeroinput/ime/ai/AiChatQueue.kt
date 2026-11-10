package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAction
import dev.zeroinput.ai.api.AiLimits

/** Owner-thread, memory-only queue. Cancellation revokes posted drains before cancelling I/O. */
internal class AiChatQueue(
    private val ready: () -> Boolean,
    private val post: (() -> Unit) -> Boolean,
    private val submit: (Message) -> Unit,
    private val cancel: () -> Unit,
    private val changed: (List<Message>, Boolean) -> Unit,
) {
    class Message(val action: AiAction, val text: String, val target: String?)
    private val pending = mutableListOf<Message>()
    private var revision = 0L
    private var running = false
    private var scheduled = false
    val busy get() = running || scheduled

    fun send(message: Message, interrupt: Boolean = false): Boolean {
        if (!ready() || message.text.isBlank() || message.text.length > AiLimits.MAX_INPUT_CHARS) return false
        if (interrupt) reset()
        if (busy) {
            if (pending.size == 4 || pending.sumOf { it.text.length } + message.text.length > AiLimits.MAX_INPUT_CHARS) return false
            pending += message
            publish()
        } else dispatch(message)
        return true
    }

    fun remove(index: Int): Message? {
        val value = pending.getOrNull(index) ?: return null
        pending.removeAt(index)
        publish()
        return value
    }

    fun completed() {
        if (!running) return
        running = false
        if (pending.isEmpty()) { publish(); return }
        scheduled = true
        val token = revision
        publish()
        if (!post {
            if (token == revision && scheduled) {
                scheduled = false
                if (!ready()) reset()
                else if (pending.isEmpty()) publish()
                else dispatch(pending.removeAt(0))
            }
        }) reset()
    }

    fun failed() { revision++; pending.clear(); running = false; scheduled = false; publish() }

    fun reset() {
        failed()
        cancel()
    }

    private fun dispatch(message: Message) {
        running = true
        publish()
        submit(message)
    }

    private fun publish() = changed(pending.toList(), busy)
}
