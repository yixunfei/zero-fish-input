package dev.zeroinput.ime.clipboard

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns a management draft across authentication and a single background write. */
internal class PendingClipboardAddition(
    value: CharArray,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private val pending = AtomicReference<CharArray?>(value)
    private val cancelled = AtomicBoolean()
    private val createdAt = now()

    fun isActive(): Boolean {
        if (now() - createdAt !in 0 until TIMEOUT_MILLIS) close()
        return !cancelled.get()
    }

    fun <T> consume(write: (CharArray) -> T): T {
        val value = pending.getAndSet(null) ?: throw CancellationException("Clipboard draft expired")
        return try {
            if (!isActive()) throw CancellationException("Clipboard draft expired")
            write(value)
        } finally {
            value.fill('\u0000')
            cancelled.set(true)
        }
    }

    override fun close() {
        cancelled.set(true)
        // A running worker owns its local buffer until its finally block; the
        // UI never waits for storage or wipes a buffer while it is being read.
        pending.getAndSet(null)?.fill('\u0000')
    }

    companion object { const val TIMEOUT_MILLIS = 30_000L }
}
