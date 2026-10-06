package dev.zeroinput.ime.glide

import dev.zeroinput.engine.api.GlideCandidate
import dev.zeroinput.engine.api.GlideDecoder
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideRequest
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.ui.GlideLetterCase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

internal data class GlideSessionIdentity(val session: Long, val interaction: Long, val layout: GlideLayout)
internal data class GlideSelection(val candidate: GlideCandidate, val letterCase: GlideLetterCase)

/** Main-thread owner. Background work only sees immutable public geometry and a cancellation ticket. */
internal class GlideCoordinator(
    private val createDecoder: () -> GlideDecoder,
    private val currentIdentity: () -> GlideSessionIdentity?,
    private val post: (Runnable) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val deliver: (List<GlideCandidate>, Boolean) -> Unit,
    private val worker: ExecutorService = BoundedExecutors.singleThread("zeroinput-glide", 1),
) : AutoCloseable {
    private val generation = AtomicLong()
    private var task: Future<*>? = null
    private var decoder: GlideDecoder? = null
    private var selectedIdentity: GlideSessionIdentity? = null
    private var selectedGeneration = -1L
    private var choices = emptyList<GlideCandidate>()
    private var letterCase = GlideLetterCase.LOWER
    private val deliveryLock = Any()
    private var delivery: Runnable? = null
    @Volatile private var closed = false

    fun request(request: GlideRequest, case: GlideLetterCase) {
        invalidate()
        val identity = currentIdentity() ?: return
        if (closed || identity.layout != request.layout) return
        letterCase = case
        val ticket = generation.get()
        try {
            task = worker.submit {
                val cancelled = { closed || generation.get() != ticket || Thread.currentThread().isInterrupted }
                if (cancelled()) return@submit
                val result = runCatching {
                    val backend = decoder ?: createDecoder().also { decoder = it }
                    if (cancelled()) emptyList() else backend.decode(request, cancelled)
                }
                if (result.isFailure) {
                    runCatching { decoder?.close() }
                    decoder = null
                }
                if (cancelled()) return@submit
                schedule(ticket, identity, result)
            }
        } catch (_: RejectedExecutionException) {
            if (currentIdentity() == identity) deliver(emptyList(), true)
        }
    }

    private fun schedule(ticket: Long, identity: GlideSessionIdentity, result: Result<List<GlideCandidate>>) {
        val callback = Runnable {
            synchronized(deliveryLock) { delivery = null }
            if (closed || generation.get() != ticket || currentIdentity() != identity) return@Runnable
            choices = result.getOrDefault(emptyList()).take(16)
            selectedIdentity = identity
            selectedGeneration = ticket
            deliver(choices, result.isFailure)
        }
        synchronized(deliveryLock) {
            if (closed || generation.get() != ticket) return
            delivery?.let(remove)
            delivery = callback
            if (!post(callback)) delivery = null
        }
    }

    fun consume(candidate: GlideCandidate): GlideSelection? {
        if (closed || selectedGeneration != generation.get() || selectedIdentity == null ||
            selectedIdentity != currentIdentity() || candidate !in choices) return null
        val selection = GlideSelection(candidate, letterCase)
        invalidate()
        return selection
    }

    fun invalidate() {
        revoke()
        task?.cancel(true); task = null
        BoundedExecutors.purge(worker)
        synchronized(deliveryLock) { delivery?.let(remove); delivery = null }
        choices = emptyList(); selectedIdentity = null; selectedGeneration = -1L
    }

    /** May run on a clear-data worker; all mutable UI/task ownership stays on the main thread. */
    fun revoke() { generation.incrementAndGet() }

    override fun close() {
        closed = true
        invalidate()
        worker.shutdownNow()
    }
}
