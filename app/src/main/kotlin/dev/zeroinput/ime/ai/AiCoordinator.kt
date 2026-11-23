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
    private var current: AiRequestHandle? = null

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
        val (token, previous) = synchronized(this) {
            generation.incrementAndGet() to current.also { current = null }
        }
        previous?.cancel()
        if (generation.get() != token) {
            request.attachments.forEach { it.bytes.fill(0) }
            return object : AiRequestHandle { override fun cancel() = Unit }
        }
        val handle = provider.stream(request) { event ->
            if (generation.get() == token) listener(event)
        }
        val installed = synchronized(this) {
            (generation.get() == token).also { if (it) current = handle }
        }
        if (!installed) handle.cancel()
        return object : AiRequestHandle {
            override fun cancel() {
                val owned = synchronized(this@AiCoordinator) {
                    if (generation.get() != token) return
                    generation.incrementAndGet()
                    current.also { current = null }
                }
                owned?.cancel()
            }
        }
    }

    fun invalidate() {
        val previous = synchronized(this) {
            generation.incrementAndGet()
            current.also { current = null }
        }
        previous?.cancel()
    }

    override fun close() = invalidate()
}
