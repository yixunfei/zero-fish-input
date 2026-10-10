package dev.zeroinput.ime

/**
 * Owns a prepared warm-up result while it is waiting for the IME owner thread.
 *
 * A Handler callback can be removed during service destruction after the
 * worker has already detached the result from [EngineWarmupCoordinator].  The
 * delivery object closes that orphaned result and makes replacement/rejection
 * paths explicit.  At most one Runnable is queued, and it always consumes the
 * newest result.  Only the callback that still owns a result may hand it to
 * the service; PreparedInputEngine makes the final close idempotent after a
 * successful controller handoff.
 */
internal class EngineWarmupResultDelivery(
    private val post: (Runnable) -> Boolean,
    private val ready: () -> Boolean = { true },
    private val removePosted: ((Runnable) -> Unit)? = null,
    private val deliver: (EngineWarmupResult) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var pending: EngineWarmupResult? = null
    private var deliveryPosted = false
    private var deliveryRunnable: Runnable? = null
    private var closed = false

    /** Offers one result and coalesces delivery onto the owner thread. */
    fun offer(result: EngineWarmupResult) {
        var accepted = false
        var replaced: EngineWarmupResult? = null
        var runnableToPost: Runnable? = null
        synchronized(lock) {
            if (!closed) {
                replaced = pending
                pending = result
                accepted = true
                if (!deliveryPosted) {
                    deliveryPosted = true
                    runnableToPost = Runnable { deliverPending() }.also { deliveryRunnable = it }
                }
            }
        }

        closePrepared(replaced)
        if (!accepted) {
            closePrepared(result)
            return
        }
        val runnable = runnableToPost ?: return
        val posted = runCatching {
            post(runnable)
        }.getOrDefault(false)
        if (!posted) cancelPostedDelivery()
    }

    /** Closes any result that has not reached the owner thread yet. */
    override fun close() {
        val (orphan, runnable) = synchronized(lock) {
            if (closed) {
                null to null
            } else {
                closed = true
                deliveryPosted = false
                pending.also { pending = null } to deliveryRunnable.also { deliveryRunnable = null }
            }
        }
        runnable?.let { runCatching { removePosted?.invoke(it) } }
        closePrepared(orphan)
    }

    /** Owner thread resumes a deferred handoff after its composition ends. */
    fun resume() {
        val runnable: Runnable? = synchronized(lock) {
            if (closed || pending == null || deliveryPosted) null
            else {
                deliveryPosted = true
                Runnable { deliverPending() }.also { deliveryRunnable = it }
            }
        }
        if (runnable != null && !runCatching { post(runnable) }.getOrDefault(false)) cancelPostedDelivery()
    }

    private fun deliverPending() {
        val canDeliver = runCatching(ready).getOrDefault(false)
        var wasClosed = false
        val result = synchronized(lock) {
            deliveryPosted = false
            deliveryRunnable = null
            wasClosed = closed
            pending.also { pending = null }
        }
        if (wasClosed || !canDeliver) {
            closePrepared(result)
            return
        }
        if (result == null) return

        // The controller may transfer the wrapped engine, in which case the
        // wrapper's close is a no-op.  Keeping this finally block means a
        // callback failure cannot leak a native session.
        try {
            deliver(result)
        } finally {
            closePrepared(result)
        }
    }

    private fun cancelPostedDelivery() {
        val (orphan, runnable) = synchronized(lock) {
            if (!deliveryPosted) {
                null to null
            } else {
                deliveryPosted = false
                pending.also { pending = null } to deliveryRunnable.also { deliveryRunnable = null }
            }
        }
        runnable?.let { runCatching { removePosted?.invoke(it) } }
        closePrepared(orphan)
    }

    private fun closePrepared(result: EngineWarmupResult?) {
        (result as? EngineWarmupResult.Prepared)?.engine?.close()
    }
}
