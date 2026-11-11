package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.security.AesGcmKeyStore
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.SecurityAliases
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** An append only write path. Directory metadata exposes opaque IDs and timestamps. */
internal class PrivateDraftEntries(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "encrypted/private-drafts")

    fun summaries(): List<SecureClipboardSummary> = files().mapNotNull { file ->
        val id = FILE.matchEntire(file.name)?.groupValues?.get(1) ?: return@mapNotNull null
        if (!file.name.endsWith(".bin") || !file.isFile) return@mapNotNull null
        SecureClipboardSummary(id, "", file.lastModified())
    }

    fun add(id: String, value: CharArray, allowKeyCreation: Boolean, beforeWrite: () -> Unit) {
        // An interrupted new-file write can leave an AtomicFile staging file.
        // The vault lock ensures there is no other writer during this cleanup.
        files().filter { it.name.endsWith(".bin.new") && FILE.matches(it.name) }.forEach {
            if (!it.delete() && it.exists()) throw IOException("Private draft staging cleanup failed")
        }
        val utf8 = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        val bytes = ByteArray(utf8.remaining())
        try {
            utf8.get(bytes)
            beforeWrite()
            store(id).writeIfActive(bytes, allowKeyCreation, beforeWrite)
        } finally {
            bytes.fill(0)
            if (utf8.hasArray()) utf8.array().fill(0)
        }
    }

    fun read(id: String): String = read(id) { it.decodeToString(throwOnInvalidSequence = true) }

    fun length(id: String): Int = read(id) { bytes ->
        val chars = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        try {
            chars.remaining()
        } finally {
            if (chars.hasArray()) chars.array().fill('\u0000')
        }
    }

    private inline fun <T> read(id: String, decode: (ByteArray) -> T): T {
        val bytes = store(id).read() ?: throw IOException("Private draft item is unavailable")
        return try {
            decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun remove(id: String) = store(id).delete(deleteKey = false)

    fun purge() {
        var failure: Exception? = null
        try {
            files().mapNotNull { FILE.matchEntire(it.name)?.groupValues?.get(1) }
                .distinct().forEach { id ->
                    try {
                        remove(id)
                    } catch (error: Exception) {
                        failure = failure?.also { it.addSuppressed(error) } ?: error
                    }
                }
        } finally {
            try {
                AesGcmKeyStore(SecurityAliases.SECURE_CLIPBOARD_DRAFTS).deleteKey()
            } catch (error: Exception) {
                failure = failure?.also { it.addSuppressed(error) } ?: error
            }
        }
        failure?.let { throw it }
    }

    private fun files(): Array<File> = directory.listFiles()
        ?: if (directory.exists()) throw IOException("Private draft index is unavailable") else emptyArray()

    private fun store(id: String) =
        EncryptedFileStore(context, "private-drafts/$id.bin", SecurityAliases.SECURE_CLIPBOARD_DRAFTS)

    private companion object {
        val FILE = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.bin(?:\\.(?:new|bak))?")
    }
}
