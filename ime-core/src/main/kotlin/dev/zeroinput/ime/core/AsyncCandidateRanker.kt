package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.CandidateScorer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One lazy worker, one replaceable request, and one content-free delivery slot. */
class AsyncCandidateRanker(
    private val createScorer: () -> CandidateScorer,
    private val post: (Runnable) -> Boolean,
    deliver: (Long, Int) -> Unit,
    private val executorFactory: () -> ExecutorService = {
        ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "zeroinput-model").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        ).apply { allowCoreThreadTimeOut(true) }
    },
    private val now: () -> Long = System::nanoTime,
) : AutoCloseable {
    private class Request(val generation: Long, val revision: Long, val deadline: Long,
        val context: CharArray, val words: Array<CharArray>) : AutoCloseable {
        override fun close() { context.fill('\u0000'); words.forEach { it.fill('\u0000') } }
    }
    private data class Result(val generation: Long, val revision: Long, val winner: Int, val deadline: Long)
    private val lock = Any()
    private val generation = AtomicLong()
    private var executor: ExecutorService? = null
    private var pending: Request? = null
    private var result: Result? = null
    private var deliveryPosted = false
    private var callback: ((Long, Int) -> Unit)? = deliver
    private var draining = false
    private var enabled = false
    private var closed = false
    // Only the worker touches these resources, including destruction.
    private var scorer: CandidateScorer? = null
    private var failed = false

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (closed || enabled == value) return@synchronized
        enabled = value
        cancelLocked()
        if (!value && executor != null) scheduleLocked()
    }

    fun cancel() = synchronized(lock) { cancelLocked() }

    /** Takes ownership of the arrays, including when disabled or executor submission fails. */
    fun request(revision: Long, context: CharArray, words: Array<CharArray>) = synchronized(lock) {
        cancelLocked()
        val request = Request(generation.get(), revision, now() + DEADLINE_NANOS, context, words)
        if (!enabled || closed || context.size !in 1..16 || words.size !in 2..8 || words.any { it.size != 2 }) {
            request.close()
            return@synchronized
        }
        pending = request
        scheduleLocked()
    }

    private fun cancelLocked() {
        generation.incrementAndGet()
        pending?.close()
        pending = null
        result = null
    }

    private fun scheduleLocked() {
        if (draining) return
        draining = true
        try {
            val worker = executor ?: executorFactory().also { executor = it }
            worker.execute(::drain)
        } catch (_: RuntimeException) {
            // Worker construction and thread creation can fail before execute
            // rejects anything. Release owned input and allow a later retry.
            draining = false
            cancelLocked()
        }
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                if (!enabled || closed) {
                    // close() is done outside the lock; the next iteration checks re-enabling.
                    null
                } else pending.also { pending = null }
            }
            if (request != null) {
                try { score(request) } finally { request.close() }
                continue
            }
            val release = synchronized(lock) { !enabled || closed }
            if (release) {
                try { scorer?.close() } catch (_: Exception) { /* Content-free failure. */ }
                scorer = null
                failed = false
            }
            synchronized(lock) {
                if (pending != null || ((!enabled || closed) && scorer != null)) return@synchronized
                draining = false
                return
            }
        }
    }

    private fun score(request: Request) {
        val cancelled = { generation.get() != request.generation || now() > request.deadline }
        if (failed || cancelled()) return
        try {
            val active = scorer ?: createScorer().also { scorer = it }
            if (cancelled()) return
            val scores = active.score(request.context, request.words, cancelled) ?: return
            try {
                if (scores.size != request.words.size || cancelled()) return
                val winner = ModelRankingPolicy.winner(scores) ?: return
                offer(Result(request.generation, request.revision, winner, request.deadline))
            } finally { scores.fill(0f) }
        } catch (_: Exception) {
            failed = true
        } catch (_: LinkageError) {
            failed = true
        }
    }

    private fun offer(value: Result) = synchronized(lock) {
        if (generation.get() != value.generation || closed || !enabled) return@synchronized
        result = value
        if (deliveryPosted) return@synchronized
        deliveryPosted = true
        val posted = try { post(Runnable { deliverResult() }) } catch (_: RuntimeException) { false }
        if (!posted) { deliveryPosted = false; result = null }
    }

    private fun deliverResult() {
        val delivery = synchronized(lock) {
            deliveryPosted = false
            val value = result.also { result = null } ?: return
            if (generation.get() != value.generation || closed || !enabled || now() > value.deadline) return
            value to callback
        }
        if (generation.get() == delivery.first.generation) delivery.second?.invoke(delivery.first.revision, delivery.first.winner)
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        enabled = false
        callback = null
        cancelLocked()
        if (executor != null) { scheduleLocked(); executor?.shutdown() }
    }

    companion object { const val DEADLINE_NANOS = 80_000_000L }
}
