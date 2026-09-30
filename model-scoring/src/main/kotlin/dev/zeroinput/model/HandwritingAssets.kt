package dev.zeroinput.model

import android.content.Context
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** Pinned public OCR weights. The asset copy contains no user input. */
internal object HandwritingAssets {
    const val MODEL_BYTES = 16_534_782L
    const val MODEL_HASH = "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092"
    const val CHARACTERS_BYTES = 74_012L
    const val CHARACTERS_HASH = "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b"

    fun model(context: Context): File {
        val directory = File(context.noBackupFilesDir, "public-model").apply { mkdirs() }
        val file = File(directory, "handwriting.onnx")
        if (file.isFile && file.length() == MODEL_BYTES && file.inputStream().use {
                MiniModelAssets.verified(it, MODEL_BYTES, MODEL_HASH)
            }) return file
        val temporary = File(directory, "handwriting.tmp")
        try {
            context.assets.open("handwriting/model.onnx").use { source ->
                temporary.outputStream().use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65_536)
                    var size = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= MODEL_BYTES) { "Invalid handwriting model" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    check(size == MODEL_BYTES && digest.digest().joinToString("") { "%02x".format(it) } == MODEL_HASH) {
                        "Invalid handwriting model"
                    }
                }
            }
            check(temporary.renameTo(file)) { "Handwriting model unavailable" }
            return file
        } finally { temporary.delete() }
    }

    fun characters(context: Context): List<String> {
        val bytes = context.assets.open("handwriting/characters.txt").use { it.readBytes() }
        check(bytes.size.toLong() == CHARACTERS_BYTES &&
            MiniModelAssets.verified(ByteArrayInputStream(bytes), CHARACTERS_BYTES, CHARACTERS_HASH)) {
            "Invalid handwriting character table"
        }
        val values = bytes.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        check(values.size == 18_383 && values.all { it.isNotEmpty() }) { "Invalid handwriting character table" }
        return listOf("") + values + " "
    }
}
