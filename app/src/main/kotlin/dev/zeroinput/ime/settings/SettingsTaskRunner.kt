package dev.zeroinput.ime.settings

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Delivers settings mutation results on the owner thread while its page still exists. */
internal class SettingsTaskRunner(
    private val worker: Executor,
    private val post: (() -> Unit) -> Unit,
    private val isActive: () -> Boolean,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun <T> execute(work: () -> T, onSuccess: (T) -> Unit) {
        if (closed.get() || !isActive()) return
        try {
            worker.execute task@{
                if (closed.get()) return@task
                val result = try {
                    Result.success(work())
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Result.failure(error)
                } catch (error: Exception) {
                    Result.failure(error)
                }
                deliver { result.fold(onSuccess) { onFailure() } }
            }
        } catch (_: RejectedExecutionException) {
            deliver(onFailure)
        }
    }

    private fun deliver(callback: () -> Unit) {
        if (closed.get()) return
        post { if (!closed.get() && isActive()) callback() }
    }

    override fun close() { closed.set(true) }
}
