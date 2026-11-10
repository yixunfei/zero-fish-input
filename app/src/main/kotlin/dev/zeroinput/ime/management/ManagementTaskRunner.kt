package dev.zeroinput.ime.management

import dev.zeroinput.ime.concurrency.BoundedExecutors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** One foreground page's worker request. Only explicit callers may start another operation. */
internal class ManagementTaskRunner(
    private val worker: ExecutorService,
    private val post: (() -> Unit) -> Unit,
    private val isActive: () -> Boolean,
    private val onBusy: (Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val generation = AtomicLong()
    private var pending: Future<*>? = null
    private var closed = false

    fun <T> execute(work: (() -> Boolean) -> T, complete: (T) -> Unit) {
        if (closed || !isActive()) return
        cancel()
        val token = generation.get()
        val current = { generation.get() == token }
        onBusy(true)
        try {
            pending = worker.submit task@{
                if (!current()) return@task
                val result = try {
                    Result.success(work { current() && !Thread.currentThread().isInterrupted })
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Result.failure(error)
                } catch (error: Exception) {
                    Result.failure(error)
                }
                post {
                    if (current() && !closed && isActive()) {
                        pending = null
                        onBusy(false)
                        result.fold(complete, onFailure)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            onBusy(false)
            onFailure(error)
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        pending?.cancel(true)
        pending = null
        BoundedExecutors.purge(worker)
        onBusy(false)
    }

    override fun close() {
        closed = true
        cancel()
    }
}
