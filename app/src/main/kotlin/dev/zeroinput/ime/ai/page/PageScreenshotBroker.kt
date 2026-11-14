package dev.zeroinput.ime.ai.page

import android.graphics.Bitmap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A single explicit screenshot capture is converted locally before any AI request. */
internal fun interface PageScreenshotSource {
    fun capture(sourcePackage: String, current: () -> Boolean, deliver: (Bitmap?) -> Unit)
}

/** Local OCR boundary. Implementations must not retain the bitmap after returning. */
internal fun interface LocalPageOcr {
    fun recognize(bitmap: Bitmap, current: () -> Boolean): PageTextSnapshot?
}

internal class PageScreenshotBroker(
    private val executor: Executor,
    private val post: (() -> Unit) -> Boolean,
    private val enabled: () -> Boolean,
    private val ocr: LocalPageOcr,
) : AutoCloseable {
    private val generation = AtomicLong()
    @Volatile private var source: PageScreenshotSource? = null

    fun attach(value: PageScreenshotSource): AutoCloseable {
        source = value
        invalidate()
        return AutoCloseable { if (source === value) { source = null; invalidate() } }
    }

    fun invalidate() { generation.incrementAndGet() }

    fun capture(sourcePackage: String, deliver: (Long, PageTextSnapshot?) -> Unit): Long {
        val token = generation.incrementAndGet()
        val reader = source
        val current = { token == generation.get() && enabled() && source === reader }
        if (reader == null || sourcePackage.isBlank() || !current()) {
            deliver(token, null)
            return token
        }
        val captured = AtomicBoolean()
        try {
            reader.capture(sourcePackage, current) bitmapReady@{ bitmap ->
                if (!captured.compareAndSet(false, true)) {
                    bitmap?.recycle()
                    return@bitmapReady
                }
                if (bitmap == null) {
                    deliverIfCurrent(token, current, deliver, null)
                    return@bitmapReady
                }
                try {
                    executor.execute {
                        val result = try {
                            if (!current()) null else ocr.recognize(bitmap, current)
                        } catch (_: RuntimeException) {
                            null
                        } finally {
                            bitmap.recycle()
                        }
                        post { deliverIfCurrent(token, current, deliver, result) }
                    }
                } catch (_: RejectedExecutionException) {
                    bitmap.recycle()
                    deliverIfCurrent(token, current, deliver, null)
                }
            }
        } catch (_: RuntimeException) {
            if (captured.compareAndSet(false, true)) deliverIfCurrent(token, current, deliver, null)
        }
        return token
    }

    private fun deliverIfCurrent(
        token: Long,
        current: () -> Boolean,
        deliver: (Long, PageTextSnapshot?) -> Unit,
        value: PageTextSnapshot?,
    ) {
        if (current() && token == generation.get()) deliver(token, value)
    }

    override fun close() { source = null; invalidate() }
}
