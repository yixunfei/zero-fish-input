package dev.zeroinput.model

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Public model only. Never stores input, token IDs, scores or optimized runtime caches. */
internal object MiniModelAssets {
    const val MODEL_BYTES = 14_898_764L
    const val MODEL_HASH = "5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446"
    const val VOCAB_BYTES = 109_540L
    const val VOCAB_HASH = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"

    fun prepare(files: Map<String, dev.zeroinput.engine.api.PublicResourceFile>): File =
        checkedFile(requireNotNull(files["mini-int8/model.onnx"]), MODEL_BYTES, MODEL_HASH)

    fun vocabulary(files: Map<String, dev.zeroinput.engine.api.PublicResourceFile>): MaskedWordTokenizer =
        MaskedWordTokenizer(checkedFile(requireNotNull(files["mini-int8/vocab.txt"]), VOCAB_BYTES, VOCAB_HASH).reader(Charsets.UTF_8))

    private fun checkedFile(file: dev.zeroinput.engine.api.PublicResourceFile, bytes: Long, hash: String): File {
        require(file.bytes == bytes && file.sha256 == hash) { "Unsupported Mini resource" }
        return File(file.path)
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
