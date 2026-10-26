package dev.zeroinput.engine.rime

import org.tukaani.xz.SingleXZInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** One bounded XZ stream, no concatenated archives, with original and compressed byte integrity. */
internal object XzDictionaryReader {
    data class Spec(val bytes: Long, val sha256: String, val archiveBytes: Long, val archiveSha256: String)

    fun copy(source: InputStream, destination: OutputStream, spec: Spec) {
        // The reviewed public table is 74,179,788 bytes. Keep a tight ceiling
        // for that larger artifact rather than accepting unbounded XZ output.
        require(spec.bytes in 1..100_000_000 && spec.archiveBytes in 1..50_000_000)
        require(spec.sha256.matches(Regex("[a-f0-9]{64}")) && spec.archiveSha256.matches(Regex("[a-f0-9]{64}")))
        val archiveHash = MessageDigest.getInstance("SHA-256")
        var archiveCount = 0L
        val bounded = object : FilterInputStream(source) {
            override fun read(): Int {
                val value = source.read()
                if (value >= 0) { record(1); archiveHash.update(value.toByte()) }
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = source.read(buffer, offset, length)
                if (count > 0) { record(count); archiveHash.update(buffer, offset, count) }
                return count
            }
            private fun record(count: Int) {
                active()
                archiveCount += count
                require(archiveCount <= spec.archiveBytes) { "Oversized dictionary archive" }
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        // Preset 6 uses an 8 MiB dictionary; reject headers demanding excessive memory.
        SingleXZInputStream(bounded, 16 * 1024).use { decoded ->
            val buffer = ByteArray(65536)
            while (true) {
                active()
                val size = decoded.read(buffer)
                if (size < 0) break
                count += size
                require(count <= spec.bytes) { "Oversized dictionary output" }
                hash.update(buffer, 0, size)
                destination.write(buffer, 0, size)
            }
            require(bounded.read() == -1 && archiveCount == spec.archiveBytes &&
                hex(archiveHash.digest()) == spec.archiveSha256) { "Damaged dictionary archive" }
        }
        require(count == spec.bytes && hex(hash.digest()) == spec.sha256) { "Damaged dictionary data" }
    }

    private fun active() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
