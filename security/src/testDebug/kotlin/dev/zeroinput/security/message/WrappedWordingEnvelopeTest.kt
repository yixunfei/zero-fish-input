package dev.zeroinput.security.message

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class WrappedWordingEnvelopeTest {
    private val profile = ByteArray(32) { 5 }
    private val stages = listOf(MessageStage(MessageCipher.TWOFISH, 192), MessageStage(MessageCipher.AES, 128),
        MessageStage(MessageCipher.XCHACHA20))

    @Test fun oneCharacterPassphraseAndIndependentFileRoundTripAndWrongFactorsFail() {
        val body = byteArrayOf(4, 0, 42, 5)
        val exported = MessageKeyFile.generate()
        assertEquals(32, exported.size)
        val file = MessageKeyFile.factor(ByteArrayInputStream(exported))
        val other = MessageKeyFile.factor(ByteArrayInputStream(exported.apply { this[0] = (this[0].toInt() xor 1).toByte() }))
        exported.fill(0)
        try {
            MessageCredential(MessageCredentialMode.PASSPHRASE_FILE, charArrayOf('a'), file).use { key ->
                val cipher = WrappedWordingEnvelope.seal(profile, body, key, stages, MessageKdf.STANDARD)
                assertEquals(WrappedWordingEnvelope.size(body.size, stages), cipher.size)
                assertEquals(MessageRequirements(key.mode, MessageKdf.STANDARD), WrappedWordingEnvelope.inspect(cipher))
                assertArrayEquals(body, WrappedWordingEnvelope.open(profile, cipher, key, MessageKdf.STANDARD))
                assertThrows(MessageFailure::class.java) { WrappedWordingEnvelope.open(profile, cipher, key, MessageKdf.NONE) }
                MessageCredential(key.mode, charArrayOf('a'), other).use { wrong ->
                    assertThrows(MessageFailure::class.java) {
                        WrappedWordingEnvelope.open(profile, cipher, wrong, MessageKdf.STANDARD)
                    }
                }
                MessageCredential(key.mode, charArrayOf('b'), file).use { wrong ->
                    assertThrows(MessageFailure::class.java) {
                        WrappedWordingEnvelope.open(profile, cipher, wrong, MessageKdf.STANDARD)
                    }
                }
            }
        } finally {
            file.fill(0); other.fill(0)
        }
    }

    @Test fun metadataContainsSeparateRandomDrawsOfExactlyTheRequestedLayerWidths() {
        var call = 0
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill((++call).toByte()) }
        }
        val derive = { _: ByteArray, _: ByteArray, _: MessageKdf -> ByteArray(32) { 7 } }
        val envelope = MessageEnvelope(random, derive)
        val payload = byteArrayOf(4, 0, 1, 0)
        MessageCredential(MessageCredentialMode.PASSPHRASE, charArrayOf('a')).use { key ->
            WrappedWordingEnvelope.binding(profile).use { binding ->
                val ciphertext = envelope.sealBoundPayload(binding, payload, key, stages, MessageKdf.STANDARD)
                val header = ciphertext.copyOfRange(0, 44)
                val salt = header.copyOfRange(0, 16)
                val length = ByteBuffer.wrap(header, 18, 2).short.toInt()
                val prk = MessageKeys.root(key, salt, MessageKdf.STANDARD, 1, derive)
                val wrapKey = MessageKeys.expand(prk, "metadata-key", header, binding.digest)
                val aad = fields(domain("metadata-aad"), header, binding.digest)
                val opened = MessagePrimitives.aead(false, MessageCipher.XCHACHA20, wrapKey,
                    header.copyOfRange(20, 44), aad, ciphertext.copyOfRange(44, 44 + length))
                try {
                    val descriptors = ByteBuffer.wrap(opened).apply { position(8) }
                    for ((index, stage) in stages.withIndex()) {
                        assertEquals(stage.cipher.id, descriptors.get().toInt())
                        assertEquals(stage.bits / 64 - 1, descriptors.get().toInt())
                        assertArrayEquals(ByteArray(stage.cipher.nonceBytes) { (2 * index + 1).toByte() },
                            ByteArray(stage.cipher.nonceBytes).also(descriptors::get))
                        assertArrayEquals(ByteArray(stage.bits / 8) { (2 * index + 2).toByte() },
                            ByteArray(stage.bits / 8).also(descriptors::get))
                    }
                    assertFalse(descriptors.hasRemaining())
                } finally { prk.fill(0); wrapKey.fill(0); aad.fill(0); opened.fill(0) }
            }
        }
    }

    @Test fun verifiesAllSixLayersAndRejectsBadSizesTamperedTagsAndOtherProfiles() {
        val body = ByteArray(MessageLimits.SECRET_BYTES) { it.toByte() }
        val all = MessageCipher.entries.map(::MessageStage)
        MessageCredential(MessageCredentialMode.KEY_FILE, charArrayOf(), ByteArray(32) { 8 }).use { key ->
            val cipher = WrappedWordingEnvelope.seal(profile, body, key, all, MessageKdf.NONE)
            assertEquals(WrappedWordingEnvelope.MAX_BYTES, cipher.size)
            assertArrayEquals(body, WrappedWordingEnvelope.open(profile, cipher, key, MessageKdf.NONE))
            assertThrows(MessageFailure::class.java) { WrappedWordingEnvelope.inspect(cipher + byteArrayOf(0)) }
            assertThrows(MessageFailure::class.java) {
                WrappedWordingEnvelope.open(profile.copyOf().apply { this[0]++ }, cipher, key, MessageKdf.NONE)
            }
            for (index in listOf(44, cipher.lastIndex)) {
                val altered = cipher.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
                assertThrows(MessageFailure::class.java) { WrappedWordingEnvelope.open(profile, altered, key, MessageKdf.NONE) }
            }
            assertThrows(MessageFailure::class.java) {
                WrappedWordingEnvelope.seal(profile, body, key, listOf(stages[0], stages[0]), MessageKdf.NONE)
            }
            for (stage in WrappedWordingEnvelope.randomizeWidths(all)) assertTrue(stage.bits in stage.cipher.keyBits)
        }
    }

    @Test fun emptyPassphraseIsRejectedAndExactTextRetainsItsMinimum() {
        val cover = "public cover".toCharArray()
        val body = "test".toCharArray()
        MessageCredential(MessageCredentialMode.PASSPHRASE, charArrayOf('a')).use { key ->
            assertThrows(MessageFailure::class.java) { MessageEnvelope().seal(cover, body, key) }
        }
        MessageCredential(MessageCredentialMode.PASSPHRASE, charArrayOf()).use { key ->
            assertThrows(MessageFailure::class.java) {
                WrappedWordingEnvelope.seal(profile, byteArrayOf(1), key, stages, MessageKdf.STANDARD)
            }
        }
    }
}
