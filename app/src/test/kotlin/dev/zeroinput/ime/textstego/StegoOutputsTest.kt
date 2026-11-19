package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import dev.zeroinput.security.message.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class StegoOutputsTest {
    private val cover = "public cover e\u0301 🌏\r\n"
    private val secret = "public secret 中文 fixture"
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test fun `all four independently owned outputs preserve ordered encryption and exact text`() {
        val stages = listOf(MessageStage(MessageCipher.SM4), MessageStage(MessageCipher.CAMELLIA, 192),
            MessageStage(MessageCipher.XCHACHA20), MessageStage(MessageCipher.AES, 128),
            MessageStage(MessageCipher.TWOFISH, 256), MessageStage(MessageCipher.KUZNYECHIK))
        MessageCredential(MessageCredentialMode.RANDOM_KEY, key.toCharArray()).use { credential ->
            val envelope = MessageEnvelope().seal(cover.toCharArray(), secret.toCharArray(), credential, stages, MessageKdf.NONE)
            for (transport in StegoTransport.entries) {
                val encoded = StegoCarrierCodec.encode(cover, envelope, transport)
                StegoOutputs(cover.toCharArray(), secret.toCharArray(), envelope, encoded).use { outputs ->
                    assertArrayEquals(secret.toCharArray(), outputs.copy(StegoOutput.PLAINTEXT))
                    assertArrayEquals(cover.toCharArray(), outputs.copy(StegoOutput.COVER))
                    assertArrayEquals(encoded.toCharArray(), outputs.copy(StegoOutput.CARRIER))
                    val detached = String(requireNotNull(outputs.copy(StegoOutput.CIPHERTEXT)))
                    assertTrue(detached.startsWith("[ZIS1:"))
                    assertFalse(detached.contains(cover))
                    val parsed = StegoCarrierCodec.decode(detached, cover)
                    val opened = MessageEnvelope().open(parsed.cover.toCharArray(), parsed.envelope, credential)
                    assertArrayEquals(secret.toCharArray(), opened)
                    opened.fill('\u0000'); parsed.envelope.fill(0)
                    // Inspection obtains requirements only, never a bypass for cover authentication.
                    val inspected = StegoCarrierCodec.inspectable(detached)
                    assertEquals(MessageCredentialMode.RANDOM_KEY, MessageEnvelope().inspect(inspected.envelope).mode)
                    assertThrows(MessageFailure::class.java) {
                        MessageEnvelope().open(inspected.cover.toCharArray(), inspected.envelope, credential)
                    }
                    inspected.envelope.fill(0)
                }
            }
            envelope.fill(0)
        }
    }

    @Test fun `wrong cover and altered detached ciphertext cannot release plaintext`() {
        MessageCredential(MessageCredentialMode.RANDOM_KEY, key.toCharArray()).use { credential ->
            val envelope = MessageEnvelope().seal(cover.toCharArray(), secret.toCharArray(), credential, kdf = MessageKdf.NONE)
            for (changedCover in listOf(cover.trim(), cover.replace("e\u0301", "é"), "different public cover")) {
                val parsed = StegoCarrierCodec.decode(StegoCarrierCodec.ciphertext(envelope), changedCover)
                assertThrows(MessageFailure::class.java) { MessageEnvelope().open(parsed.cover.toCharArray(), parsed.envelope, credential) }
                parsed.envelope.fill(0)
            }
            envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
            val parsed = StegoCarrierCodec.decode(StegoCarrierCodec.ciphertext(envelope), cover)
            assertThrows(MessageFailure::class.java) { MessageEnvelope().open(parsed.cover.toCharArray(), parsed.envelope, credential) }
            parsed.envelope.fill(0); envelope.fill(0)
        }
    }

    @Test fun `saved copies survive disposal while disposed outputs cannot be copied again`() {
        val sourceCover = cover.toCharArray()
        val sourceSecret = secret.toCharArray()
        val envelope = byteArrayOf(1, 20)
        val encoded = StegoCarrierCodec.encode(cover, envelope, StegoTransport.VISIBLE_BASE64)
        val outputs = StegoOutputs(sourceCover, sourceSecret, envelope, encoded)
        sourceCover.fill('\u0000'); sourceSecret.fill('\u0000'); envelope.fill(0)
        val selected = requireNotNull(outputs.copy(StegoOutput.PLAINTEXT))
        outputs.close()
        assertArrayEquals(secret.toCharArray(), selected)
        StegoOutput.entries.forEach { assertNull(outputs.copy(it)) }
        selected.fill('\u0000')
    }
}
