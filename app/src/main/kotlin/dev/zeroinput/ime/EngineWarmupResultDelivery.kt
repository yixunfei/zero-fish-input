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
    private val deliver: (EngineWarmupResult) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var pending: EngineWarmupResult? = null
    private var deliveryPosted = false
    private var closed = false

    /** Offers one result and coalesces delivery onto the owner thread. */
    fun offer(result: EngineWarmupResult) {
        var accepted = false
        var replaced: EngineWarmupResult? = null
        var shouldPost = false
        synchronized(lock) {
            if (!closed) {
                replaced = pending
                pending = result
                accepted = true
                if (!deliveryPosted) {
                    deliveryPosted = true
                    shouldPost = true
                }
            }
        }

        closePrepared(replaced)
        if (!accepted) {
            closePrepared(result)
            return
        }
        if (!shouldPost) return

        val posted = runCatching {
            post(Runnable { deliverPending() })
        }.getOrDefault(false)
        if (!posted) cancelPostedDelivery()
    }

    /** Closes any result that has not reached the owner thread yet. */
    override fun close() {
        val orphan = synchronized(lock) {
            if (closed) {
                null
            } else {
                closed = true
                deliveryPosted = false
                pending.also { pending = null }
            }
        }
        closePrepared(orphan)
    }

    /** Owner thread resumes a deferred handoff after its composition ends. */
    fun resume() {
        val shouldPost = synchronized(lock) {
            if (closed || pending == null || deliveryPosted) false
            else { deliveryPosted = true; true }
        }
        if (shouldPost && !runCatching { post(Runnable { deliverPending() }) }.getOrDefault(false)) {
            cancelPostedDelivery()
        }
    }

    private fun deliverPending() {
        val canDeliver = runCatching(ready).getOrDefault(false)
        var wasClosed = false
        val result = synchronized(lock) {
            deliveryPosted = false
            wasClosed = closed
            if (!closed && !canDeliver) null else pending.also { pending = null }
        }
        if (wasClosed) {
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
        val orphan = synchronized(lock) {
            if (!deliveryPosted) {
                null
            } else {
                deliveryPosted = false
                pending.also { pending = null }
            }
        }
        closePrepared(orphan)
    }

    private fun closePrepared(result: EngineWarmupResult?) {
        (result as? EngineWarmupResult.Prepared)?.engine?.close()
    }
}
