package dev.zeroinput.security.message

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MessageRandomKeyTest {
    @Test fun `custom master lengths round trip every layer without changing cipher widths`() {
        for (bits in listOf(256, 264, 512, 1024, 4096)) {
            val chars = MessageRandomKey.generate(bits)
            val another = MessageRandomKey.generate(bits)
            try {
                assertEquals(bits / 8, Base64.getUrlDecoder().decode(String(chars)).size)
                assertFalse(chars.contentEquals(another))
                for (mode in listOf(MessageCredentialMode.RANDOM_KEY, MessageCredentialMode.RANDOM_KEY_FILE)) {
                    MessageCredential(mode, chars, if (mode.file) ByteArray(32) { 7 } else null).use { key ->
                        val crypto = MessageEnvelope()
                        val cover = "public cover".toCharArray()
                        val text = "public secret".toCharArray()
                        val envelope = crypto.seal(cover, text, key, MessageCipher.entries.map(::MessageStage), MessageKdf.NONE)
                        assertArrayEquals(text, crypto.open(cover, envelope, key))
                        MessageCredential(mode, another, if (mode.file) ByteArray(32) { 7 } else null).use { wrong ->
                            assertThrows(MessageFailure::class.java) { crypto.open(cover, envelope, wrong) }
                        }
                    }
                }
            } finally { chars.fill('\u0000'); another.fill('\u0000') }
        }
    }

    @Test fun `invalid lengths and noncanonical encodings fail closed`() {
        for (bits in listOf(-8, 0, 128, 255, 257, 4097, 8192)) {
            assertFalse(MessageRandomKey.validBits(bits))
            assertThrows(MessageFailure::class.java) { MessageRandomKey.generate(bits) }
        }
        val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
        for (value in listOf(canonical + "=", canonical.dropLast(1) + "B", " " + canonical,
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(513)))) {
            MessageCredential(MessageCredentialMode.RANDOM_KEY, value.toCharArray()).use { key ->
                assertThrows(MessageFailure::class.java) {
                    MessageEnvelope().seal("cover".toCharArray(), "secret".toCharArray(), key, kdf = MessageKdf.NONE)
                }
            }
        }
    }
}
