package dev.zeroinput.ime.ai

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import dev.zeroinput.ai.api.AiAttachment
import dev.zeroinput.ai.api.AiLimits

/** Reads only a user-granted document, with a hard cap even for lying providers. */
internal object AiAttachmentReader {
    fun read(resolver: ContentResolver, uri: Uri, cancellation: CancellationSignal = CancellationSignal()): AiAttachment {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val mime = resolver.getType(uri)?.lowercase()?.substringBefore(';')
            ?: throw IllegalArgumentException("Unknown attachment type")
        require(mime in setOf("text/plain", "image/jpeg", "image/png", "image/webp",
            "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3"))
        val normalized = when (mime) {
            "audio/x-wav" -> "audio/wav"
            "audio/mp3" -> "audio/mpeg"
            else -> mime
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().take(128).filterNot(Char::isISOControl).ifBlank { "attachment" }
        val buffer = ByteArray(AiLimits.MAX_ATTACHMENT_BYTES + 1)
        try {
            var size = 0
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation)
                ?: throw IllegalArgumentException("Attachment unavailable")
            cancellation.setOnCancelListener { runCatching { descriptor.close() } }
            descriptor.use { it.createInputStream().use { input ->
                while (size < buffer.size) {
                    cancellation.throwIfCanceled()
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    require(count > 0)
                    size += count
                }
            } }
            require(size in 1..AiLimits.MAX_ATTACHMENT_BYTES)
            val bytes = buffer.copyOf(size)
            return try { AiAttachment(normalized, bytes, name) }
                catch (error: Exception) { bytes.fill(0); throw error }
        } finally { cancellation.setOnCancelListener(null); buffer.fill(0) }
    }
}
