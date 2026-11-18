package dev.zeroinput.ime.textstego

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.zeroinput.security.message.MessageKeyFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One temporary content URI read. Closing also revokes an in-flight provider open/pipe. */
internal class StegoKeyFileRead : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val signal = CancellationSignal()
    private val descriptor = AtomicReference<ParcelFileDescriptor?>()

    fun read(resolver: ContentResolver, uri: Uri, current: () -> Boolean): ByteArray? {
        fun valid() = active.get() && current()
        return try {
            if (uri.scheme != "content" || !valid()) return null
            val opened = resolver.openFileDescriptor(uri, "r", signal) ?: return null
            descriptor.set(opened)
            if (!valid()) return null
            ParcelFileDescriptor.AutoCloseInputStream(opened).use {
                MessageKeyFile.factor(it, opened.statSize.takeIf { size -> size >= 0 }, ::valid)
            }
        } catch (_: Exception) { null }
        finally { close() }
    }

    override fun close() {
        active.set(false)
        signal.cancel()
        runCatching { descriptor.getAndSet(null)?.close() }
    }
}
