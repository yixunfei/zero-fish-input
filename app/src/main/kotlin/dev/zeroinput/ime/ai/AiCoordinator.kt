package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiGenerationPolicy
import dev.zeroinput.ai.api.AiProvider
import dev.zeroinput.ai.api.AiRequest
import dev.zeroinput.ai.api.AiRequestHandle
import dev.zeroinput.ai.api.AiStreamEvent
import java.util.concurrent.atomic.AtomicLong

/** Owns request generations so late network callbacks cannot update a new editor. */
class AiCoordinator(private val provider: AiProvider) : AutoCloseable {
    private val generation = AtomicLong()
    @Volatile private var current: AiRequestHandle? = null

    fun submit(
        request: AiRequest,
        policy: AiGenerationPolicy,
        listener: (AiStreamEvent) -> Unit,
    ): AiRequestHandle {
        try { policy.check(request) } catch (error: Exception) {
            // Ownership has not crossed the provider boundary yet.
            request.attachments.forEach { it.bytes.fill(0) }
            throw error
        }
        val token = generation.incrementAndGet()
        current?.cancel()
        val handle = provider.stream(request) { event ->
            if (generation.get() == token) listener(event)
        }
        synchronized(this) {
            if (generation.get() == token) current = handle else handle.cancel()
        }
        return object : AiRequestHandle {
            override fun cancel() {
                if (generation.compareAndSet(token, token + 1)) {
                    handle.cancel()
                    synchronized(this@AiCoordinator) {
                        if (current === handle) current = null
                    }
                }
            }
        }
    }

    @Synchronized
    fun invalidate() {
        generation.incrementAndGet()
        current?.cancel()
        current = null
    }

    override fun close() = invalidate()
}
