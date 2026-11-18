package dev.zeroinput.security

/** Encrypted persistence. Callers clear read buffers; writes consume and clear their input. */
interface EncryptedStore {
    fun read(): ByteArray?
    /** Takes ownership and clears [plaintext] on success, failure and cancellation; retries need fresh input. */
    fun write(plaintext: ByteArray)
    fun delete(deleteKey: Boolean = true)
}
