package dev.zeroinput.ime.ai.page

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

internal fun interface PageTextSource {
    fun capture(sourcePackage: String, current: () -> Boolean): PageTextSnapshot
}

/** Process-owned rendezvous with no retained page text, Activity or editor connection. */
internal class PageReferenceBroker(
    private val executor: Executor,
    private val post: (() -> Unit) -> Boolean,
    private val enabled: () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var source: PageTextSource? = null
    private val generation = AtomicLong()
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    val connected: Boolean get() = source != null

    fun attach(value: PageTextSource): AutoCloseable {
        source = value
        invalidate()
        return AutoCloseable {
            if (source === value) { source = null; invalidate() }
        }
    }

    fun observeInvalidation(observer: () -> Unit): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    fun invalidate() {
        generation.incrementAndGet()
        observers.forEach { it() }
    }

    fun isCurrent(token: Long): Boolean = token == generation.get() && enabled() && source != null

    /** Called only from the current IME's explicit capture action. The caller rechecks its session on delivery. */
    fun capture(sourcePackage: String, deliver: (Long, PageTextSnapshot?) -> Unit): Long {
        val token = generation.incrementAndGet()
        val reader = source
        val deadline = now() + CAPTURE_TIMEOUT_MS
        val current = { isCurrent(token) && source === reader && now() < deadline }
        if (reader == null || sourcePackage.isBlank() || !current()) {
            deliver(token, null)
            return token
        }
        try {
            executor.execute {
                if (!current()) return@execute
                val snapshot = runCatching { reader.capture(sourcePackage, current) }.getOrNull()
                post { if (isCurrent(token)) deliver(token, snapshot.takeIf { now() < deadline }) }
            }
        } catch (_: RejectedExecutionException) { deliver(token, null) }
        return token
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 5_000L
        const val REVIEW_LIFETIME_MS = 30_000L
    }
}
