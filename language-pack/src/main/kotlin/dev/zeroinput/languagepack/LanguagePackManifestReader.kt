package dev.zeroinput.languagepack

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Reads a manifest with a byte bound and strict UTF-8 decoding. */
internal object LanguagePackManifestReader {
    fun read(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0) { "Manifest byte limit must be positive" }
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "Language pack manifest is too large" }
            bytes.write(buffer, 0, count)
        }
        require(total > 0) { "Language pack manifest is empty" }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
