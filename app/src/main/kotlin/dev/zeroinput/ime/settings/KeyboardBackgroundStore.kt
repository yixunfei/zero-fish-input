package dev.zeroinput.ime.settings

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.KeyboardMaterial
import dev.zeroinput.security.EncryptedFileStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** One serial storage owner; no application-wide plaintext/bitmap cache. */
internal class KeyboardBackgroundStore(context: Context, private val settings: SettingsRepository,
                                       private val resolver: android.content.ContentResolver = context.contentResolver,
                                       private val keyAlias: String = "zeroinput.keyboard-background.v1") : AutoCloseable {
    private val application = context.applicationContext
    private val worker = BoundedExecutors.singleThread("keyboard-background", 4)
    private val cancellationWorker = BoundedExecutors.singleThread("keyboard-image-cancel", 4)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val requests = CopyOnWriteArrayList<Request<*>>()
    private var mutation: AutoCloseable? = null

    init { cleanupLater(settings.keyboardAppearance.imageRevision, generation.get()) }

    fun load(appearance: KeyboardAppearance, completed: (Bitmap?) -> Unit): AutoCloseable {
        val value = appearance.sanitized()
        val token = generation.get()
        return submit(null, { bitmap: Bitmap? ->
            if (generation.get() == token) completed(bitmap) else { bitmap?.recycle(); completed(null) }
        }, { it?.recycle() }) { signal ->
            if (value.background != KeyboardBackground.IMAGE || value.imageRevision.isEmpty()) return@submit null
            val file = File(application.noBackupFilesDir, "encrypted/$PREFIX${value.imageRevision}.bin")
            require(listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
                .all { it.length() <= BackgroundImageCodec.MAX_BYTES + 64 })
            val bytes = store(value.imageRevision).read() ?: return@submit null
            try {
                signal.throwIfCanceled()
                val decoded = BackgroundImageCodec.decode(bytes)
                val blur = maxOf(value.backgroundBlur, if (value.material == KeyboardMaterial.FROSTED) 8 else 0)
                BackgroundImageCodec.blur(decoded, blur)
            } finally { bytes.fill(0) }
        }
    }

    fun import(uri: Uri, completed: (Boolean) -> Unit): AutoCloseable {
        mutation?.close()
        val token = generation.incrementAndGet()
        val id = UUID.randomUUID().toString()
        val request = submit(false, { success: Boolean ->
            if (success && generation.get() == token) {
                settings.keyboardAppearance = settings.keyboardAppearance.copy(background = KeyboardBackground.IMAGE, imageRevision = id)
                cleanupLater(id, token)
                completed(true)
            } else { discardImage(id); completed(false) }
        }, { _: Boolean -> discardImage(id) }) { signal ->
            val bytes = BackgroundImageCodec.read(resolver, uri, signal)
            try {
                signal.throwIfCanceled()
                check(generation.get() == token)
                store(id).write(bytes)
                signal.throwIfCanceled()
                check(generation.get() == token)
                true
            } finally { bytes.fill(0) }
        }
        mutation = request
        return request
    }

    /** Revoke imports immediately; serial deletion prevents queued writes from restoring a removed image. */
    fun remove(completed: (Boolean) -> Unit): AutoCloseable {
        mutation?.close()
        generation.incrementAndGet()
        settings.keyboardAppearance = settings.keyboardAppearance.copy(background = KeyboardBackground.SOLID, imageRevision = "")
        return submit(false, completed, {}, durable = true) { _ ->
            cleanup("")
            dev.zeroinput.security.AesGcmKeyStore(keyAlias).deleteKey()
            true
        }.also { mutation = it }
    }

    private fun store(id: String) = EncryptedFileStore(application, "$PREFIX$id.bin", keyAlias)

    private fun cleanupLater(keep: String, token: Long) {
        try { worker.execute { if (generation.get() == token) runCatching { cleanup(keep) } } }
        catch (_: RejectedExecutionException) { /* Retried on next mutation. */ }
    }

    private fun discardImage(id: String) {
        try { worker.execute { runCatching { store(id).delete(deleteKey = false) } } }
        catch (_: RejectedExecutionException) { /* An encrypted orphan is removed by the next successful mutation. */ }
    }

    private fun cleanup(keep: String) {
        val directory = File(application.noBackupFilesDir, "encrypted")
        val retained = if (keep.isEmpty()) emptySet() else setOf("$PREFIX$keep.bin", "$PREFIX$keep.bin.bak", "$PREFIX$keep.bin.new")
        directory.listFiles()?.filter { it.name.startsWith(PREFIX) && it.name !in retained }?.forEach {
            check(it.delete() || !it.exists()) { "Background removal failed" }
        }
    }

    private fun <T> submit(fallback: T, completed: (T) -> Unit, discard: (T) -> Unit, durable: Boolean = false,
                           work: (CancellationSignal) -> T): AutoCloseable {
        val request = Request(completed, discard, durable)
        requests += request
        val timeout = Runnable { request.finish(fallback) }
        request.timeout = timeout
        main.postDelayed(timeout, 15_000)
        try {
            request.future = worker.submit {
                val result = try {
                    if (!durable) request.signal.throwIfCanceled()
                    work(request.signal)
                } catch (_: Exception) { fallback }
                main.post { request.finish(result) }
            }
        } catch (_: RejectedExecutionException) { main.post { request.finish(fallback) } }
        return request
    }

    private inner class Request<T>(private var callback: ((T) -> Unit)?, private val discard: (T) -> Unit,
                                    private val durable: Boolean) : AutoCloseable {
        val signal = CancellationSignal()
        var future: java.util.concurrent.Future<*>? = null
        var timeout: Runnable? = null
        fun finish(value: T) {
            val action = callback
            callback = null
            close()
            if (action != null) action(value) else discard(value)
        }
        override fun close() {
            callback = null
            if (!durable) { future?.cancel(false); BoundedExecutors.purge(worker) }
            timeout?.let(main::removeCallbacks)
            requests -= this
            try { cancellationWorker.execute { signal.cancel() } } catch (_: RejectedExecutionException) { /* Store already closed. */ }
        }
    }

    override fun close() {
        generation.incrementAndGet()
        requests.toList().forEach { it.close() }
        worker.shutdownNow()
        cancellationWorker.shutdown()
    }

    private companion object {
        const val PREFIX = "keyboard-background-"
    }
}
