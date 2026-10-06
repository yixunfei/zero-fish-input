package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAttachment
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cancelling queued work releases bytes immediately; running work releases them in finally. */
internal class AiRequestResources(private val attachments: List<AiAttachment>) {
    private var running = false
    private var released = false

    @Synchronized fun start(): Boolean {
        if (released) return false
        running = true
        return true
    }

    @Synchronized fun cancel() { if (!running) release() }
    @Synchronized fun finish() { running = false; release() }

    private fun release() {
        if (released) return
        released = true
        attachments.forEach { it.bytes.fill(0) }
    }
}

/** At most four live deadlines across workbench and settings probes; no transport on this thread. */
internal object AiRequestTimeouts {
    private val slots = Semaphore(4)
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "zeroinput-ai-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        if (!slots.tryAcquire()) throw RejectedExecutionException()
        val released = AtomicBoolean()
        val release = { if (released.compareAndSet(false, true)) slots.release() }
        val future = try {
            worker.schedule({ try { action() } finally { release() } }, delayMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) { release(); throw error }
        return AutoCloseable { future.cancel(false); release() }
    }
}
