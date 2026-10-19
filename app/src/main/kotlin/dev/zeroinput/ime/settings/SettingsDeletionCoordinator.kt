package dev.zeroinput.ime.settings

import dev.zeroinput.ime.concurrency.BoundedExecutors
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

internal enum class SettingsDeletion { PERSONALIZATION, SECURE_CLIPBOARD, AI }
internal data class SettingsDeletionResult(val kind: SettingsDeletion, val success: Boolean)

/** Application-owned confirmed deletions; results survive settings page recreation. */
internal class SettingsDeletionCoordinator(
    private val post: (() -> Unit) -> Unit,
    private val delete: (SettingsDeletion, (Boolean) -> Unit) -> Unit,
    private val worker: ExecutorService = BoundedExecutors.singleThread("zeroinput-settings-deletion", 3),
) : AutoCloseable {
    private class Pending(var result: Boolean? = null)
    private class Observer(val active: () -> Boolean, val receive: (SettingsDeletionResult) -> Unit)
    private val lock = Any()
    private val pending = EnumMap<SettingsDeletion, Pending>(SettingsDeletion::class.java)
    private var observer: Observer? = null
    private var closed = false

    fun submit(kind: SettingsDeletion) {
        val operation = synchronized(lock) {
            if (closed || pending.containsKey(kind)) return
            Pending().also { pending[kind] = it }
        }
        try {
            worker.execute {
                try { delete(kind) { finish(kind, operation, it) } }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    finish(kind, operation, false)
                } catch (_: Exception) { finish(kind, operation, false) }
            }
        } catch (_: RejectedExecutionException) { finish(kind, operation, false) }
    }

    fun observe(isActive: () -> Boolean = { true }, listener: (SettingsDeletionResult) -> Unit): AutoCloseable {
        val registration = Observer(isActive, listener)
        synchronized(lock) {
            if (closed) return AutoCloseable {}
            observer = registration
        }
        deliver()
        return AutoCloseable { synchronized(lock) { if (observer === registration) observer = null } }
    }

    private fun finish(kind: SettingsDeletion, operation: Pending, success: Boolean) {
        synchronized(lock) {
            if (closed || pending[kind] !== operation || operation.result != null) return
            operation.result = success
        }
        deliver()
    }

    private fun deliver() {
        val target = synchronized(lock) { observer } ?: return
        post {
            while (true) {
                val result = synchronized(lock) {
                    if (closed || observer !== target || !target.active()) return@post
                    val next = pending.entries.firstOrNull { it.value.result != null } ?: return@post
                    SettingsDeletionResult(next.key, checkNotNull(next.value.result)).also { pending.remove(next.key) }
                }
                target.receive(result)
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; observer = null; pending.clear() }
        worker.shutdownNow()
    }
}
