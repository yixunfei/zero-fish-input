package dev.zeroinput.userdata

import dev.zeroinput.security.AesGcmKeyStore

/** Small port so export/import behavior can be tested without an Android Keystore. */
interface EncryptedExportCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray
    fun hasKey(): Boolean = true
    fun deleteKey() = Unit
}

internal class KeyStoreExportCipher(alias: String) : EncryptedExportCipher {
    private val delegate = AesGcmKeyStore(alias)

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.encrypt(plaintext, associatedData)

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.decrypt(payload, associatedData)

    override fun hasKey(): Boolean = delegate.hasKey()

    override fun deleteKey() = delegate.deleteKey()
}
