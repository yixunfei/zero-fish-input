package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A fixed 4 MiB public-artwork cache. Asset I/O and decoding never run on the IME thread. */
internal object EmojiArtwork {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val worker = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "emoji-artwork").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, key: String, callback: (Bitmap?) -> Unit): Closeable {
        cache.get(key)?.let { callback(it); return Closeable {} }
        val registration = Registration(callback)
        val assets = context.applicationContext.assets
        try {
            registration.future = worker.submit {
                if (!registration.isClosed) {
                    val bitmap = cache.get(key) ?: decode(assets, key)?.also { cache.put(key, it) }
                    if (!registration.isClosed) main.post { registration.deliver(bitmap) }
                }
            }
        } catch (_: RejectedExecutionException) {
            main.post { registration.deliver(null) }
        }
        return registration
    }

    private fun decode(assets: android.content.res.AssetManager, key: String): Bitmap? = try {
        assets.open("emoji/${RgiEmojiVersion.VERSION}/images/$key.webp").use { stream ->
            BitmapFactory.decodeStream(stream)?.takeIf { it.width == 128 && it.height == 128 }
        }
    } catch (_: IOException) {
        null
    }

    private class Registration(callback: (Bitmap?) -> Unit) : Closeable {
        @Volatile private var callback: ((Bitmap?) -> Unit)? = callback
        var future: Future<*>? = null
        val isClosed: Boolean get() = callback == null
        fun deliver(bitmap: Bitmap?) { callback?.invoke(bitmap); callback = null }
        override fun close() {
            callback = null
            future?.cancel(false)
            (future as? Runnable)?.let(worker::remove)
            future = null
        }
    }
}
