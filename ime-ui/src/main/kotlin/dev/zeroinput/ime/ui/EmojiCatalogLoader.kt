package dev.zeroinput.ime.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One process-wide worker, with cancellable listeners that never retain a detached view. */
internal object EmojiCatalogLoader {
    private val lock = Any()
    private val listeners = mutableListOf<Registration>()
    private var preparing = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "emoji-catalog").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

    fun prepare(context: Context, callback: (Boolean) -> Unit): Closeable {
        val registration = Registration(callback)
        synchronized(lock) {
            if (EmojiCatalog.isReady) {
                main.post { registration.deliver(true) }
                return registration
            }
            listeners.removeAll { it.isClosed }
            if (listeners.size >= 16) {
                main.post { registration.deliver(false) }
                return registration
            }
            listeners += registration
            if (!preparing) {
                preparing = true
                worker.execute { load(context) }
            }
        }
        return registration
    }

    private fun load(context: Context) {
        val ready = try {
            val entries = context.assets.open("emoji/${RgiEmojiVersion.VERSION}/catalog.tsv").use(RgiEmojiData::read)
            EmojiCatalog.install(entries)
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        val callbacks = synchronized(lock) {
            preparing = false
            listeners.toList().also { listeners.clear() }
        }
        main.post { callbacks.forEach { it.deliver(ready) } }
    }

    private class Registration(callback: (Boolean) -> Unit) : Closeable {
        @Volatile private var callback: ((Boolean) -> Unit)? = callback
        val isClosed: Boolean get() = callback == null
        fun deliver(ready: Boolean) { callback?.invoke(ready); close() }
        override fun close() { callback = null }
    }
}
