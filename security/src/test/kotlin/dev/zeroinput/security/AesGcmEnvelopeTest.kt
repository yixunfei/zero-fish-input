package dev.zeroinput.security

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class AesGcmEnvelopeTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val aad = "public file binding".toByteArray()
    private val plaintext = "public fixture".toByteArray()

    @Test fun roundTripsEmptyAndUnicodePlaintextUsingIndependentRandomIvs() {
        for (value in listOf(byteArrayOf(), plaintext, "公开构造文字 😀".toByteArray())) {
            val first = AesGcmEnvelope.encrypt(value, aad, key)
            val second = AesGcmEnvelope.encrypt(value, aad, key)
            assertFalse(first.contentEquals(second))
            assertFalse(first.copyOfRange(8, 20).contentEquals(second.copyOfRange(8, 20)))
            val restored = AesGcmEnvelope.decrypt(first, aad) { key }
            try { assertArrayEquals(value, restored) } finally { restored.fill(0) }
        }
    }

    @Test fun existingVersionOneJceEnvelopesRemainReadable() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        val envelope = ByteBuffer.allocate(8 + cipher.iv.size + encrypted.size)
            .putInt(1).putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        assertArrayEquals(plaintext, AesGcmEnvelope.decrypt(envelope, aad) { key })
    }

    @Test fun writesPreserveVersionOneLayoutForAnIndependentJceReader() {
        val envelope = ByteBuffer.wrap(AesGcmEnvelope.encrypt(plaintext, aad, key))
        assertEquals(1, envelope.int)
        val iv = ByteArray(envelope.int).also(envelope::get)
        val encrypted = ByteArray(envelope.remaining()).also(envelope::get)
        val reader = Cipher.getInstance("AES/GCM/NoPadding")
        reader.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        reader.updateAAD(aad)
        assertArrayEquals(plaintext, reader.doFinal(encrypted))
    }

    @Test fun malformedHeadersAndTruncatedTagsFailBeforeKeyAccess() {
        val original = AesGcmEnvelope.encrypt(plaintext, aad, key)
        val invalid = listOf(byteArrayOf(), original.copyOf(10),
            original.copyOf().apply { ByteBuffer.wrap(this).putInt(2) },
            original.copyOf().apply { ByteBuffer.wrap(this).putInt(4, Int.MAX_VALUE) },
            original.copyOf().apply { ByteBuffer.wrap(this).putInt(4, -1) },
            original.copyOf().apply { ByteBuffer.wrap(this).putInt(4, 32) })
        var requests = 0
        for (value in invalid) assertThrows(IllegalArgumentException::class.java) {
            AesGcmEnvelope.decrypt(value, aad) { requests++; key }
        }
        assertEquals(0, requests)
    }

    @Test fun wrongKeyFileBindingCiphertextAndTagCannotProducePlaintext() {
        val envelope = AesGcmEnvelope.encrypt(plaintext, aad, key)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.decrypt(envelope, aad) { other } }
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.decrypt(envelope, byteArrayOf(1)) { key } }
        for (offset in listOf(8, 20, envelope.lastIndex)) {
            val changed = envelope.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.decrypt(changed, aad) { key } }
        }
    }
}
