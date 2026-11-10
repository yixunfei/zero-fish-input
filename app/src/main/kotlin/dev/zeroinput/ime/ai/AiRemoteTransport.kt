package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiConnection
import java.io.Reader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AiRemoteCancellation(private val current: () -> Boolean) {
    private val cancelled = AtomicBoolean()
    private val action = AtomicReference<(() -> Unit)?>(null)
    val dispatched = AtomicBoolean()
    fun live() = !cancelled.get() && current()
    fun check() { check(live()) { "Remote operation expired" } }
    fun bind(cancel: () -> Unit) { action.set(cancel); if (!live()) cancel() }
    fun unbind() { action.set(null) }
    fun cancel() { cancelled.set(true); action.getAndSet(null)?.invoke() }
}
internal class AiRemoteReply(val status: Int, val body: String, val session: String? = null) {
    override fun toString() = "AiRemoteReply(status=$status)"
}
internal interface AiRemoteTransport {
    fun mcp(connection: AiConnection, body: String, session: String?, cancellation: AiRemoteCancellation): AiRemoteReply
    fun search(connection: AiConnection, query: String, cancellation: AiRemoteCancellation): AiRemoteReply
}

internal object AiRemoteWire {
    /** Stops at one complete RPC response; never waits for a persistent SSE connection to close. */
    fun read(reader: Reader, sse: Boolean, current: () -> Boolean): String {
        val body = StringBuilder(); val line = StringBuilder(); var total = 0; var skipLf = false
        fun lineFinished(): String? {
            val text = line.toString(); line.setLength(0)
            if (text.isEmpty()) {
                if (body.isNotEmpty()) return body.toString().trimEnd('\n')
            } else if (text.startsWith("data:")) {
                val payload = text.substring(5).removePrefix(" ")
                if (body.isNotEmpty() || payload.isNotBlank()) body.append(payload).append('\n')
            } else require(text.startsWith(':') || text.startsWith("id:") || text.startsWith("retry:") || text == "event: message")
            return null
        }
        try {
            while (true) {
                check(current()); val next = reader.read()
                if (next < 0) { require(!sse); return body.toString() }
                require(++total <= 262_144)
                if (!sse) { body.append(next.toChar()); continue }
                if (skipLf) { skipLf = false; if (next == 10) continue }
                if (next == 10 || next == 13) {
                    skipLf = next == 13
                    lineFinished()?.let { return it }
                } else { require(line.length < 131_072); line.append(next.toChar()) }
            }
        } finally { body.setLength(0); line.setLength(0) }
    }
}
