package dev.zeroinput.ime.ai

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One explicitly picked text URI; bounded, cancellation-aware, with no retained grant. */
internal object AiDocumentTextReader {
    fun read(resolver: ContentResolver, uri: Uri, cancellation: CancellationSignal,
        maxBytes: Int, current: () -> Boolean): String = readAccepted(resolver, uri, cancellation, maxBytes,
        setOf("text/plain", "text/markdown", "text/x-markdown"), current)

    fun readFile(resolver: ContentResolver, uri: Uri, cancellation: CancellationSignal,
        current: () -> Boolean): String = readAccepted(resolver, uri, cancellation, 65_536,
        setOf("text/plain", "text/markdown", "text/x-markdown", "application/json", "text/csv"), current)

    private fun readAccepted(resolver: ContentResolver, uri: Uri, cancellation: CancellationSignal,
        maxBytes: Int, mimeTypes: Set<String>, current: () -> Boolean): String {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && maxBytes in 1..65_536)
        check(current()); cancellation.throwIfCanceled()
        require(resolver.getType(uri)?.substringBefore(';')?.lowercase() in mimeTypes)
        check(current()); cancellation.throwIfCanceled()
        val buffer = ByteArray(maxBytes + 1)
        val characters = CharBuffer.allocate(maxBytes + 1)
        try {
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation) ?: error("Document unavailable")
            cancellation.setOnCancelListener { runCatching { descriptor.close() } }
            var size = 0
            descriptor.use { asset -> asset.createInputStream().use { input ->
                while (size < buffer.size) {
                    cancellation.throwIfCanceled(); check(current())
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    require(count > 0); size += count
                }
            } }
            require(size in 1..maxBytes); check(current()); cancellation.throwIfCanceled()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(buffer, 0, size), characters, true).throwExceptionIfInvalid()
            decoder.flush(characters).throwExceptionIfInvalid(); characters.flip()
            return characters.toString().also { require(it.none { char -> char.isISOControl() && char !in "\n\r\t" }) }
        } finally { cancellation.setOnCancelListener(null); buffer.fill(0); characters.array().fill('\u0000') }
    }

    private fun java.nio.charset.CoderResult.throwExceptionIfInvalid() { if (isError) throwException(); check(!isOverflow) }
}
