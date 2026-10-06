package dev.zeroinput.ime.handwriting

import android.os.Handler
import android.os.Looper
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.model.OfflineHandwritingRecognizer
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal interface HandwritingRecognizer : AutoCloseable {
    fun recognize(strokes: List<FloatArray>, cancelled: () -> Boolean): List<String>
}

internal class OnnxHandwritingRecognizer(private val delegate: OfflineHandwritingRecognizer) : HandwritingRecognizer {
    override fun recognize(strokes: List<FloatArray>, cancelled: () -> Boolean): List<String> =
        delegate.recognize(strokes, cancelled)
    override fun close() = delegate.close()
}

/** Owns each copied stroke buffer until it is cancelled or the worker finishes. */
internal class HandwritingRequest(private val strokes: List<FloatArray>) {
    private val state = AtomicInteger(0) // 0 queued, 1 running, 2 wiped

    fun run(block: (List<FloatArray>) -> Unit) {
        if (!state.compareAndSet(0, 1)) return
        try { block(strokes) } finally { wipe() }
    }

    fun cancelQueued() {
        if (state.compareAndSet(0, 2)) strokes.forEach { it.fill(0f) }
    }

    private fun wipe() {
        strokes.forEach { it.fill(0f) }
        state.set(2)
    }

    companion object {
        fun copyOf(strokes: List<FloatArray>): HandwritingRequest? {
            if (strokes.size !in 1..48 || strokes.any { stroke ->
                stroke.size !in 2..1024 || stroke.size % 2 != 0 ||
                    stroke.any { !it.isFinite() }
            }) return null
            return HandwritingRequest(strokes.map { stroke ->
                stroke.clone().also { copy ->
                    for (index in copy.indices) copy[index] = copy[index].coerceIn(0f, 1f)
                }
            })
        }
    }
}

/** Main-thread owner of debouncing, one worker, and replaceable session-only results. */
internal class HandwritingCoordinator(
    private val handler: Handler,
    private val createRecognizer: () -> HandwritingRecognizer,
    private val deliver: (List<String>?, Boolean) -> Unit,
) : AutoCloseable {
    private val worker = BoundedExecutors.singleThread("zeroinput-handwriting", queueCapacity = 1)
    private val generation = AtomicLong()
    private var pending: Runnable? = null
    private var pendingRequest: HandwritingRequest? = null
    private var task: Future<*>? = null
    private var taskRequest: HandwritingRequest? = null
    private var recognizer: HandwritingRecognizer? = null
    private val deliveryLock = Any()
    private var delivery: Runnable? = null
    @Volatile private var closed = false

    fun request(strokes: List<FloatArray>) {
        check(Looper.myLooper() == handler.looper)
        invalidate()
        if (closed || strokes.isEmpty()) return
        val request = HandwritingRequest.copyOf(strokes) ?: run {
            deliver(emptyList(), false)
            return
        }
        val ticket = generation.get()
        val runnable = Runnable {
            pending = null
            pendingRequest = null
            if (closed || generation.get() != ticket) {
                request.cancelQueued()
                return@Runnable
            }
            try {
                taskRequest = request
                task = worker.submit {
                    request.run { snapshot ->
                        val cancelled = { closed || generation.get() != ticket || Thread.currentThread().isInterrupted }
                        val outcome = runCatching {
                            if (cancelled()) emptyList() else {
                                val engine = recognizer ?: createRecognizer().also { recognizer = it }
                                engine.recognize(snapshot, cancelled)
                            }
                        }
                        if (outcome.isFailure) {
                            runCatching { recognizer?.close() }
                            recognizer = null
                        }
                        if (!cancelled()) {
                            lateinit var callback: Runnable
                            callback = Runnable {
                                synchronized(deliveryLock) { if (delivery === callback) delivery = null }
                                if (!closed && generation.get() == ticket) {
                                    deliver(outcome.getOrNull(), outcome.isFailure)
                                }
                            }
                            synchronized(deliveryLock) {
                                if (!closed && generation.get() == ticket) {
                                    delivery?.let(handler::removeCallbacks)
                                    delivery = callback
                                    if (!handler.post(callback)) delivery = null
                                }
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                request.cancelQueued()
                taskRequest = null
                if (!closed && generation.get() == ticket) deliver(null, true)
            }
        }
        pending = runnable
        pendingRequest = request
        if (!handler.postDelayed(runnable, DEBOUNCE_MS)) {
            pending = null
            pendingRequest = null
            request.cancelQueued()
        }
    }

    fun invalidate() {
        val ticket = generation.incrementAndGet()
        if (Looper.myLooper() == handler.looper) clearPending()
        else handler.post { if (generation.get() == ticket) clearPending() }
    }

    private fun clearPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
        pendingRequest?.cancelQueued()
        pendingRequest = null
        task?.cancel(true)
        task = null
        taskRequest?.cancelQueued()
        taskRequest = null
        synchronized(deliveryLock) { delivery?.let(handler::removeCallbacks); delivery = null }
        BoundedExecutors.purge(worker)
    }

    override fun close() {
        check(Looper.myLooper() == handler.looper)
        if (closed) return
        closed = true
        invalidate()
        try {
            worker.execute { runCatching { recognizer?.close() }; recognizer = null }
        } catch (_: RejectedExecutionException) { /* The worker has already stopped. */ }
        worker.shutdown()
    }

    private companion object {
        const val DEBOUNCE_MS = 180L
    }
}
