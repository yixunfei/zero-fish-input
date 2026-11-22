package dev.zeroinput.ime.textstego

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.zeroinput.security.message.MessageKeyFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Writes one new standalone file selected through the foreground document picker. */
internal class StegoKeyFileWrite : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val signal = CancellationSignal()
    private val descriptor = AtomicReference<ParcelFileDescriptor?>()

    fun write(resolver: ContentResolver, uri: Uri, current: () -> Boolean): Boolean {
        fun valid() = active.get() && current()
        var bytes: ByteArray? = null
        return try {
            if (uri.scheme != "content" || !valid()) return false
            val opened = resolver.openFileDescriptor(uri, "rw", signal) ?: return false
            descriptor.set(opened)
            if (!valid() || opened.statSize != 0L) return false
            bytes = MessageKeyFile.generate()
            if (!valid()) return false
            ParcelFileDescriptor.AutoCloseOutputStream(opened).use { it.write(bytes) }
            valid()
        } catch (_: Exception) { false }
        finally { bytes?.fill(0); close() }
    }

    override fun close() {
        active.set(false)
        signal.cancel()
        runCatching { descriptor.getAndSet(null)?.close() }
    }
}
