package dev.zeroinput.ime.clipboard

import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.userdata.SecureClipboardVault
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/** Main-thread owner with one bounded, cancellable editor read off the input thread. */
internal class ClipboardSelectionReader(
    private val post: (() -> Unit) -> Unit,
    private val worker: ExecutorService = BoundedExecutors.singleThread("zeroinput-copy-selection", 1),
) : AutoCloseable {
    private var pending: Read? = null

    fun read(length: Int, selectedText: () -> CharSequence?, isCurrent: () -> Boolean,
        result: (ClipboardImportRequest?) -> Unit) {
        cancel()
        if (length !in 1..SecureClipboardVault.MAX_VALUE_LENGTH || !isCurrent()) {
            result(null)
            return
        }
        val request = Read()
        pending = request
        try {
            request.task = worker.submit {
                if (request.cancelled) return@submit
                val value = try {
                    selectedText()?.takeIf { it.length == length }?.let { ClipboardImportText.parse(it) }
                } catch (_: RuntimeException) { null }
                request.offer(value)
                post {
                    if (pending === request) {
                        pending = null
                        val draft = request.take()
                        if (isCurrent()) result(draft) else draft?.close()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            cancel()
            result(null)
        }
    }

    fun cancel() {
        pending?.close()
        pending = null
        BoundedExecutors.purge(worker)
    }

    override fun close() { cancel(); worker.shutdownNow() }

    private class Read : AutoCloseable {
        @Volatile var cancelled = false
        var task: Future<*>? = null
        private var draft: ClipboardImportRequest? = null

        @Synchronized fun offer(value: ClipboardImportRequest?) {
            if (cancelled) value?.close() else draft = value
        }
        @Synchronized fun take(): ClipboardImportRequest? = draft.also { draft = null }
        @Synchronized override fun close() {
            cancelled = true
            task?.cancel(true)
            draft?.close()
            draft = null
        }
    }
}
