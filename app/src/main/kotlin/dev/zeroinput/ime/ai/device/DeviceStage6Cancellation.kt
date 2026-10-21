package dev.zeroinput.ime.ai.device

/**
 * Thread-safe cancellation fan-out for a single Stage 6 operation.
 *
 * The platform adapter registers a resource-closing callback (for example a
 * ContentResolver CancellationSignal), so a blocking provider read can be
 * interrupted even when it ignores the cooperative stillValid check.
 */
internal class DeviceStage6Cancellation {
    private val lock = Any()
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun register(listener: () -> Unit): AutoCloseable {
        val immediate = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                listeners += listener
                false
            }
        }
        if (immediate) {
            runCatching(listener)
            return AutoCloseable {}
        }
        return AutoCloseable { synchronized(lock) { listeners.remove(listener) } }
    }

    fun cancel() {
        val callbacks = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { runCatching(it) }
    }
}
