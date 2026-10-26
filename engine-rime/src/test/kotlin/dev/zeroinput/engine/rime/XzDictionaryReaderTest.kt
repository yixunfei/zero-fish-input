package dev.zeroinput.engine.rime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class XzDictionaryReaderTest {
    private val data = "公开词表\tni hao\t100\n".repeat(200).toByteArray()
    private fun archive(options: LZMA2Options = LZMA2Options(6)): ByteArray = ByteArrayOutputStream().also {
        XZOutputStream(it, options).use { stream -> stream.write(data) }
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun spec(bytes: ByteArray) = XzDictionaryReader.Spec(data.size.toLong(), hash(data), bytes.size.toLong(), hash(bytes))

    @Test fun restoresExactOriginalBytes() {
        val archive = archive()
        val output = ByteArrayOutputStream()
        XzDictionaryReader.copy(archive.inputStream(), output, spec(archive))
        assertArrayEquals(data, output.toByteArray())
    }

    @Test fun rejectsTruncationCorruptionTrailingStreamsAndWrongLengths() {
        val archive = archive()
        val damaged = archive.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 4).toByte() }
        for (input in listOf(archive.copyOf(archive.size - 5), damaged, archive + archive)) {
            assertThrows(Exception::class.java) {
                XzDictionaryReader.copy(input.inputStream(), ByteArrayOutputStream(), spec(archive))
            }
        }
        for (invalid in listOf(spec(archive).copy(bytes = 1), spec(archive).copy(archiveBytes = 1),
            spec(archive).copy(sha256 = "0".repeat(64)), spec(archive).copy(archiveSha256 = "0".repeat(64)))) {
            assertThrows(Exception::class.java) {
                XzDictionaryReader.copy(archive.inputStream(), ByteArrayOutputStream(), invalid)
            }
        }
    }

    @Test fun rejectsMalformedHashMetadataAndRetainsLargeDefaultBudget() {
        val archive = archive()
        for (invalid in listOf(spec(archive).copy(sha256 = "not-hex"),
            spec(archive).copy(archiveSha256 = "0".repeat(63)))) {
            assertThrows(IllegalArgumentException::class.java) {
                XzDictionaryReader.copy(archive.inputStream(), ByteArrayOutputStream(), invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XzDictionaryReader.copy(archive.inputStream(), ByteArrayOutputStream(),
                spec(archive).copy(bytes = 100_000_001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XzDictionaryReader.copy(archive.inputStream(), ByteArrayOutputStream(),
                spec(archive).copy(archiveBytes = 50_000_001))
        }
    }

    @Test fun rejectsExcessiveDecoderMemoryAndCancellation() {
        // Fixed public fixture advertising a 32 MiB dictionary; do not allocate a large encoder in tests.
        val largeDictionary = java.util.Base64.getDecoder().decode(
            "/Td6WFoAAATm1rRGAgAhARoAAADMkDPpAQAFcHVibGljAAAArH0eceUI8yYAAR4GwS+kHR+2830BAAAAAARZWg==")
        assertThrows(org.tukaani.xz.MemoryLimitException::class.java) {
            XzDictionaryReader.copy(largeDictionary.inputStream(), ByteArrayOutputStream(), spec(largeDictionary))
        }
        val archive = archive()
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) {
                XzDictionaryReader.copy(archive.inputStream(), ByteArrayOutputStream(), spec(archive))
            }
        } finally { Thread.interrupted() }
    }
}
