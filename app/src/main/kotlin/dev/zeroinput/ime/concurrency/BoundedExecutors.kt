package dev.zeroinput.ime.concurrency

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Creates the small, bounded workers used by the application layer.
 *
 * An IME can receive lifecycle callbacks faster than encrypted storage or
 * native initialization can finish.  An unbounded executor would retain every
 * cancelled callback in that situation and turn a short burst into a long
 * tail of stale work.  Rejection is intentional: callers keep the result in
 * memory and can retry on the next lifecycle boundary instead of blocking the
 * input thread.
 */
internal object BoundedExecutors {
    fun singleThread(
        name: String,
        queueCapacity: Int,
        priority: Int = Thread.NORM_PRIORITY - 1,
    ): ExecutorService {
        require(queueCapacity > 0) { "Queue capacity must be positive" }
        val factory = ThreadFactory { task ->
            Thread(task, name).apply {
                this.priority = priority.coerceIn(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY)
            }
        }
        return ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            factory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    /** Removes cancelled futures that are still waiting in a bounded queue. */
    fun purge(executor: ExecutorService) {
        (executor as? ThreadPoolExecutor)?.purge()
    }

    /**
     * Drops work waiting in a pool owned by a higher-priority control
     * operation. The caller must have invalidated the dropped work first;
     * queued tasks are never running, so removing them cannot interrupt an
     * active operation.
     */
    fun cancelQueued(executor: ExecutorService) {
        cancelQueued(executor) { true }
    }

    /** Removes only queued tasks selected by the caller, preserving control work. */
    fun cancelQueued(executor: ExecutorService, shouldCancel: (Runnable) -> Boolean) {
        val pool = executor as? ThreadPoolExecutor ?: return
        val queued = pool.queue.toList()
        queued.forEach { task ->
            if (!shouldCancel(task)) return@forEach
            (task as? Future<*>)?.cancel(false)
            pool.remove(task)
        }
        pool.purge()
    }
}
