package dev.zeroinput.ime.clipboard

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns a management draft across authentication and a single background write. */
internal class PendingClipboardAddition(value: CharArray) : AutoCloseable {
    private val pending = AtomicReference<CharArray?>(value)
    private val cancelled = AtomicBoolean()

    fun isActive(): Boolean = !cancelled.get()

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
}
