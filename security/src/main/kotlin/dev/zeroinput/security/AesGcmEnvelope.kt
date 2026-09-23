package dev.zeroinput.security

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Format 1 envelope; key ownership and platform access stay in AesGcmKeyStore. */
internal object AesGcmEnvelope {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT_VERSION = 1
    private const val HEADER_SIZE = Int.SIZE_BYTES * 2
    private const val TAG_SIZE_BITS = 128
    private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / Byte.SIZE_BITS
    private const val MIN_IV_SIZE = 12
    private const val MAX_IV_SIZE = 32

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        try {
            val iv = cipher.iv
            return ByteBuffer.allocate(HEADER_SIZE + iv.size + ciphertext.size)
                .putInt(FORMAT_VERSION).putInt(iv.size).put(iv).put(ciphertext).array()
        } finally { ciphertext.fill(0) }
    }

    fun decrypt(envelope: ByteArray, associatedData: ByteArray, key: () -> SecretKey): ByteArray {
        require(envelope.size >= HEADER_SIZE + MIN_IV_SIZE + TAG_SIZE_BYTES) { "Encrypted payload is truncated" }
        val buffer = ByteBuffer.wrap(envelope)
        require(buffer.int == FORMAT_VERSION) { "Unsupported encrypted payload version" }
        val ivSize = buffer.int
        require(ivSize in MIN_IV_SIZE..MAX_IV_SIZE && buffer.remaining() >= ivSize + TAG_SIZE_BYTES) {
            "Invalid encrypted payload IV"
        }
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        } finally { iv.fill(0); ciphertext.fill(0) }
    }
}
