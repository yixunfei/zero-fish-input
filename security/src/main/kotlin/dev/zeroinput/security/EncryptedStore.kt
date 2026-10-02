package dev.zeroinput.security

/** Encrypted persistence. Callers clear read buffers; writes consume and clear their input. */
interface EncryptedStore {
    fun read(): ByteArray?
    fun write(plaintext: ByteArray)
    fun delete(deleteKey: Boolean = true)
}
