package dev.zeroinput.userdata

import android.util.JsonReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes encrypted plaintext bytes into a JsonReader without materialising
 * the whole document as an un-wipeable String.  The decoded CharBuffer backs
 * the reader directly and is zeroed after [read] returns.  Per-token String
 * values produced by JsonReader remain a documented boundary of the Android
 * JSON model (String content is not wipeable).
 */
internal fun <T> withJsonReader(bytes: ByteArray, read: (JsonReader) -> T): T {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    // UTF-8 produces at most one UTF-16 unit per input byte. Own the buffer
    // before decoding so malformed input also clears partially decoded text.
    val characters = CharBuffer.allocate(bytes.size)
    try {
        decoder.decode(ByteBuffer.wrap(bytes), characters, true).throwIfError()
        decoder.flush(characters).throwIfError()
        characters.flip()
        return JsonReader(WipeableReader(characters)).use(read)
    } finally {
        characters.array().fill('\u0000')
    }
}

/**
 * Serves characters straight from the wipeable buffer without copying them
 * into a String.  JsonReader buffers reads and never asks the Reader to
 * re-read consumed characters, so each character is handed out exactly once.
 */
private class WipeableReader(private val source: CharBuffer) : Reader() {
    override fun read(target: CharArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length)
        if (length == 0) return 0
        if (!source.hasRemaining()) return -1
        val count = minOf(length, source.remaining())
        source.get(target, offset, count)
        return count
    }

    override fun read(): Int = if (source.hasRemaining()) source.get().code else -1

    override fun close() = Unit
}

private fun java.nio.charset.CoderResult.throwIfError() {
    if (isError) throwException()
    check(!isOverflow) { "JSON decoding capacity exceeded" }
}
