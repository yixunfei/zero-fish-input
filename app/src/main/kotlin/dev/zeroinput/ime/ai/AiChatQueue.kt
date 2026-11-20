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
    private var active: Message? = null
    var paused = false
        private set
    val hasEntries get() = pending.isNotEmpty()
    private var running = false
    private var scheduled = false
    val busy get() = running || scheduled

    fun send(message: Message, interrupt: Boolean = false): Boolean {
        if (!ready() || message.text.isBlank() || message.text.length > AiLimits.MAX_INPUT_CHARS) return false
        if (interrupt) reset()
        // Keep at most one active prompt plus the original four-message queue budget.
        if (paused && (pending.size >= 5 || pending.sumOf { it.text.length } + message.text.length >
                2 * AiLimits.MAX_INPUT_CHARS)) return false
        if (busy) {
            if (pending.size >= 4 || pending.sumOf { it.text.length } + message.text.length > AiLimits.MAX_INPUT_CHARS) return false
            pending += message
            publish()
        } else dispatch(message)
        return true
    }

    fun remove(index: Int): Message? {
        if (!ready()) { reset(); return null }
        val value = pending.getOrNull(index) ?: return null
        pending.removeAt(index)
        if (pending.isEmpty()) paused = false
        publish()
        return value
    }

    fun retry(index: Int): Boolean {
        if (busy || !paused) return false
        val message = remove(index) ?: return false
        dispatch(message)
        return true
    }

    fun answered() { active = null }

    fun completed() {
        if (!running) return
        running = false
        active = null
        if (paused || pending.isEmpty()) { publish(); return }
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

    fun failed(recoverActive: Boolean = true) {
        revision++
        if (!ready()) pending.clear()
        else if (recoverActive) active?.let { pending.add(0, it) }
        active = null
        running = false
        scheduled = false
        paused = pending.isNotEmpty()
        publish()
    }

    fun reset() {
        pending.clear()
        failed(recoverActive = false)
        cancel()
    }

    private fun dispatch(message: Message) {
        active = message
        running = true
        publish()
        submit(message)
    }

    private fun publish() = changed(pending.toList(), busy)
}
