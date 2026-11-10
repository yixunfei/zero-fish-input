package dev.zeroinput.ime.ai

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Shared cancellation boundary for private library views, independent of their record schema. */
internal class AiLibrarySession<T>(
    private val executor: Executor,
    private val data: AiDataGeneration,
    private val post: (() -> Unit) -> Boolean,
    private val deliver: (Result<T>) -> Unit,
) : AutoCloseable {
    private val generation = AtomicLong()
    private val dataToken = data.current()
    @Volatile private var closed = false

    fun run(work: (() -> Boolean) -> T) {
        val token = generation.incrementAndGet()
        val current = { !closed && generation.get() == token && data.isCurrent(dataToken) }
        if (!current()) return
        try {
            executor.execute {
                if (!current()) return@execute
                val result = runCatching { work(current) }
                post { if (current()) deliver(result) }
            }
        } catch (_: RejectedExecutionException) {
            if (current()) deliver(Result.failure(IllegalStateException("Library unavailable")))
        }
    }

    override fun close() { closed = true; generation.incrementAndGet() }
}
