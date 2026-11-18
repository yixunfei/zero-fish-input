package dev.zeroinput.security.message

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MessageEnvelopeTest {
    private val cover = "公开文字 👩‍💻\r\n".toCharArray()
    private val secret = "公开测试秘密 e\u0301 🌏".toCharArray()
    private fun credential(mode: MessageCredentialMode = MessageCredentialMode.RANDOM_KEY,
                           file: ByteArray? = null) = MessageCredential(mode,
        if (mode == MessageCredentialMode.KEY_FILE) charArrayOf() else if (mode.password)
            "public fixture passphrase".toCharArray() else
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }).toCharArray(), file)

    @Test fun `every cipher key width and ordered mixed cascade authenticates exact Unicode`() {
        val crypto = MessageEnvelope()
        val combinations = MessageCipher.entries.flatMap { cipher -> cipher.keyBits.map { listOf(MessageStage(cipher, it)) } } +
            listOf(MessageCipher.entries.map(::MessageStage), MessageCipher.entries.reversed().map(::MessageStage))
        credential().use { key -> combinations.forEach { stages ->
            val sealed = crypto.seal(cover, secret, key, stages, MessageKdf.NONE)
            assertArrayEquals(secret, crypto.open(cover, sealed, key))
            val frame = ((String(secret).toByteArray().size + 99) / 64) * 64
            assertEquals(frame + 68 + stages.size * 30 + if (stages.any { it.cipher == MessageCipher.XCHACHA20 }) 12 else 0, sealed.size)
        } }
    }

    @Test fun `any changed byte altered cover or wrong factor releases no plaintext`() {
        val crypto = MessageEnvelope()
        credential().use { key ->
            val sealed = crypto.seal(cover, secret, key, MessageCipher.entries.map(::MessageStage), MessageKdf.NONE)
            for (index in sealed.indices) {
                val changed = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
                assertThrows("offset $index", MessageFailure::class.java) { crypto.open(cover, changed, key) }
            }
            assertThrows(MessageFailure::class.java) { crypto.open("changed cover".toCharArray(), sealed, key) }
            assertThrows(MessageFailure::class.java) { crypto.open(cover, sealed + byteArrayOf(0), key) }
            assertThrows(MessageFailure::class.java) { crypto.open(cover, sealed.copyOf(sealed.size - 1), key) }
            MessageCredential(MessageCredentialMode.RANDOM_KEY, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toCharArray()).use {
                assertThrows(MessageFailure::class.java) { crypto.open(cover, sealed, it) }
            }
        }
    }

    @Test fun `one password KDF per message including six layers and preparation self check`() {
        var calls = 0
        val crypto = MessageEnvelope(SecureRandom()) { input, salt, _ ->
            calls++
            java.security.MessageDigest.getInstance("SHA-256").digest(input + salt)
        }
        credential(MessageCredentialMode.PASSPHRASE_FILE, ByteArray(32) { 7 }).use { key ->
            val sealed = crypto.seal(cover, secret, key, MessageCipher.entries.map(::MessageStage))
            assertEquals(1, calls)
            assertArrayEquals(secret, crypto.open(cover, sealed, key))
            assertEquals(2, calls)
            sealed[17] = 4
            assertThrows(MessageFailure::class.java) { crypto.open(cover, sealed, key) }
            assertEquals(2, calls)
        }
    }

    @Test fun `all five credential modes require every selected factor and exact passphrase`() {
        val crypto = MessageEnvelope(SecureRandom()) { input, salt, _ -> MessageKeys.argon(input, salt, MessageKdf.STANDARD) }
        MessageCredentialMode.entries.forEach { mode ->
            credential(mode, if (mode.file) ByteArray(32) { 5 } else null).use { key ->
                val sealed = crypto.seal(cover, secret, key, kdf = if (mode.password) MessageKdf.STANDARD else MessageKdf.NONE)
                assertArrayEquals(secret, crypto.open(cover, sealed, key))
                if (mode.file) credential(mode, ByteArray(32) { 6 }).use { wrong ->
                    assertThrows(MessageFailure::class.java) { crypto.open(cover, sealed, wrong) }
                }
            }
        }
    }

    @Test fun `invalid bounds stages widths and unavailable KDF reject before expensive work`() {
        var calls = 0
        val crypto = MessageEnvelope(SecureRandom()) { _, _, _ -> calls++; ByteArray(32) }
        assertThrows(IllegalArgumentException::class.java) { MessageStage(MessageCipher.SM4, 256) }
        credential().use { key ->
            assertThrows(MessageFailure::class.java) { crypto.seal(cover, secret, key, emptyList(), MessageKdf.NONE) }
            assertThrows(MessageFailure::class.java) { crypto.seal(cover, secret, key, List(2) { MessageStage(MessageCipher.AES) }, MessageKdf.NONE) }
            assertThrows(MessageFailure::class.java) { crypto.seal(cover, CharArray(2049) { 'a' }, key, kdf = MessageKdf.NONE) }
            assertThrows(MessageFailure::class.java) { crypto.seal(charArrayOf('\ud800'), secret, key, kdf = MessageKdf.NONE) }
        }
        credential(MessageCredentialMode.PASSPHRASE).use { key ->
            val sealed = crypto.seal(cover, secret, key, kdf = MessageKdf.HIGH_MEMORY)
            assertEquals(1, calls)
            for (invalidCover in listOf(charArrayOf('\ud800'), CharArray(513) { 'a' }, charArrayOf(' '))) {
                assertThrows(MessageFailure::class.java) { crypto.open(invalidCover, sealed, key) { true } }
                assertEquals(1, calls)
            }
            assertThrows(MessageFailure::class.java) { crypto.open(cover, sealed, key) }
            assertEquals(1, calls)
        }
    }

    @Test fun `file stream checks actual size read errors cancellation and exact bytes`() {
        val bytes = ByteArray(128) { it.toByte() }
        val factor = MessageKeyFile.factor(ByteArrayInputStream(bytes))
        assertArrayEquals(factor, MessageKeyFile.factor(ByteArrayInputStream(bytes), 128))
        assertFalse(factor.contentEquals(MessageKeyFile.factor(ByteArrayInputStream(bytes + byteArrayOf(1)))))
        listOf(0, 31, MessageLimits.KEY_FILE_BYTES + 1).forEach { size ->
            assertThrows(MessageFailure::class.java) { MessageKeyFile.factor(ByteArrayInputStream(ByteArray(size))) }
        }
        assertThrows(MessageFailure::class.java) { MessageKeyFile.factor(ByteArrayInputStream(bytes), 129) }
        assertThrows(MessageFailure::class.java) { MessageKeyFile.factor(ByteArrayInputStream(bytes), isCurrent = { false }) }
        assertThrows(IOException::class.java) { MessageKeyFile.factor(object : InputStream() { override fun read(): Int = throw IOException("fixture") }) }
    }

    @Test fun `maximum six layer message matches revision two size and fresh randomness`() {
        val crypto = MessageEnvelope()
        credential().use { key ->
            val payload = CharArray(2048) { 'x' }
            val a = crypto.seal(cover, payload, key, MessageCipher.entries.map(::MessageStage), MessageKdf.NONE)
            val b = crypto.seal(cover, payload, key, MessageCipher.entries.map(::MessageStage), MessageKdf.NONE)
            assertEquals(2372, a.size)
            assertFalse(a.contentEquals(b))
            assertArrayEquals(payload, crypto.open(cover, a, key))
        }
    }
}
