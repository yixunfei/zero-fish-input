package dev.zeroinput.model

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Public model only. Never stores input, token IDs, scores or optimized runtime caches. */
internal object MiniModelAssets {
    const val MODEL_BYTES = 14_898_764L
    const val MODEL_HASH = "5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446"
    const val VOCAB_BYTES = 109_540L
    const val VOCAB_HASH = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"

    fun prepare(context: Context): File {
        val directory = File(context.noBackupFilesDir, "public-model").apply { mkdirs() }
        val file = File(directory, "mini-int8.onnx")
        if (file.isFile && file.length() == MODEL_BYTES && file.inputStream().use { verified(it, MODEL_BYTES, MODEL_HASH) }) return file
        val temporary = Files.createTempFile(directory.toPath(), "mini-int8-", ".tmp").toFile()
        try {
            context.assets.open("mini-int8/model.onnx").use { source ->
                temporary.outputStream().use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65_536)
                    var size = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= MODEL_BYTES) { "Invalid model asset" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    check(size == MODEL_BYTES && hex(digest.digest()) == MODEL_HASH) { "Invalid model asset" }
                }
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return file
        } finally { temporary.delete() }
    }

    fun vocabulary(context: Context): MaskedWordTokenizer {
        context.assets.open("mini-int8/vocab.txt").use {
            check(verified(it, VOCAB_BYTES, VOCAB_HASH)) { "Invalid model vocabulary" }
        }
        return MaskedWordTokenizer(context.assets.open("mini-int8/vocab.txt").reader(Charsets.UTF_8))
    }

    internal fun verified(source: InputStream, expectedBytes: Long, expectedHash: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var size = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            size += count
            if (size > expectedBytes) return false
            digest.update(buffer, 0, count)
        }
        return size == expectedBytes && hex(digest.digest()) == expectedHash
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
