package dev.zeroinput.ime.ai

import java.util.concurrent.atomic.AtomicLong

/** Invalidates queued AI persistence when configuration or user data is cleared. */
class AiDataGeneration {
    private val value = AtomicLong()

    fun current(): Long = value.get()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(expected: Long): Boolean = value.get() == expected
}
