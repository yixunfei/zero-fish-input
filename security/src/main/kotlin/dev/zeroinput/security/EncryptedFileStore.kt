package dev.zeroinput.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets

class EncryptedFileStore(
    context: Context,
    fileName: String,
    keyAlias: String,
) : EncryptedStore {
    private val file = File(context.noBackupFilesDir, "encrypted/$fileName")
    private val atomicFile = AtomicFile(file)
    private val cipher = AesGcmKeyStore(keyAlias)
    private val associatedData = "zeroinput:$fileName:v1".toByteArray(StandardCharsets.UTF_8)
    private val lock = Any()

    override fun read(): ByteArray? = synchronized(lock) {
        try {
            val encrypted = atomicFile.readFully()
            try {
                cipher.decrypt(encrypted, associatedData)
            } finally {
                encrypted.fill(0)
            }
        } catch (_: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) {
                throw IOException("Encrypted data could not be read")
            }
            null
        }
    }

    override fun write(plaintext: ByteArray) = synchronized(lock) {
        var encrypted: ByteArray? = null
        var output: java.io.FileOutputStream? = null
        try {
            file.parentFile?.mkdirs()
            encrypted = cipher.encrypt(plaintext, associatedData)
            output = atomicFile.startWrite()
            output.write(encrypted)
            atomicFile.finishWrite(output)
            output = null
            // AtomicFile logs rename failures without throwing; callers must not publish an unsaved update.
            if (!file.isFile || File(file.path + ".new").exists() || File(file.path + ".bak").exists()) {
                throw IOException("Encrypted data could not be committed")
            }
        } catch (error: Throwable) {
            output?.let { atomicFile.failWrite(it) }
            throw error
        } finally {
            plaintext.fill(0)
            encrypted?.fill(0)
        }
    }

    override fun delete(deleteKey: Boolean) = synchronized(lock) {
        try { atomicFile.delete() } finally { if (deleteKey) cipher.deleteKey() }
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any(File::exists)) {
            throw IOException("Encrypted data deletion is incomplete")
        }
    }
}
