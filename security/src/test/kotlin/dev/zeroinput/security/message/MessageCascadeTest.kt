package dev.zeroinput.security.message

import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MessageCascadeTest {
    private val cover = "public cover".toCharArray()
    private val plain = "public secret fixture".toCharArray()
    private fun credential() = MessageCredential(MessageCredentialMode.RANDOM_KEY,
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }).toCharArray())

    @Test fun `all six algorithm orderings and mixed key widths authenticate without fallback`() {
        val crypto = MessageEnvelope()
        credential().use { key ->
            val orders = permutations(MessageCipher.entries.toList())
            assertEquals(720, orders.size)
            orders.forEach { order ->
                val sealed = crypto.seal(cover, plain, key, order.map(::MessageStage), MessageKdf.NONE)
                val opened = crypto.open(cover, sealed, key)
                assertArrayEquals(plain, opened)
                opened.fill('\u0000'); sealed.fill(0)
            }
            for (aes in listOf(128, 192, 256)) for (twofish in listOf(128, 192, 256)) for (camellia in listOf(128, 192, 256)) {
                val stages = listOf(MessageStage(MessageCipher.AES, aes), MessageStage(MessageCipher.TWOFISH, twofish),
                    MessageStage(MessageCipher.CAMELLIA, camellia))
                val sealed = crypto.seal(cover, plain, key, stages, MessageKdf.NONE)
                val opened = crypto.open(cover, sealed, key)
                assertArrayEquals(plain, opened)
                opened.fill('\u0000'); sealed.fill(0)
            }
        }
    }

    @Test fun `valid outer layers never release a damaged inner layer plaintext`() {
        val crypto = MessageEnvelope()
        credential().use { key ->
            val sealed = crypto.seal(cover, plain, key, MessageCipher.entries.map(::MessageStage), MessageKdf.NONE)
            for (index in 0..5) {
                val changed = damagedLayer(sealed, key, index)
                assertThrows("Damaged layer $index", MessageFailure::class.java) { crypto.open(cover, changed, key) }
                changed.fill(0)
            }
        }
    }

    /** Re-authenticates only outer fixture layers, so failure must reach the damaged inner tag. */
    private fun damagedLayer(sealed: ByteArray, credential: MessageCredential, damaged: Int): ByteArray {
        val header = sealed.copyOfRange(0, 44)
        val length = ByteBuffer.wrap(header, 18, 2).short.toInt() and 65535
        val binding = hash(fields(domain("exact-cover"), utf8(cover, MessageLimits.COVER_BYTES)))
        val root = MessageKeys.root(credential, header.copyOfRange(0, 16), MessageKdf.NONE, 1)
        val metadataKey = MessageKeys.expand(root, "metadata-key", header, binding)
        val metadata = MessagePrimitives.aead(false, MessageCipher.XCHACHA20, metadataKey,
            header.copyOfRange(20, 44), fields(domain("metadata-aad"), header, binding), sealed.copyOfRange(44, 44 + length))
        val transcript = hash(fields(domain("descriptor"), header, metadata, binding))
        val buffer = ByteBuffer.wrap(metadata).apply { position(8) }
        val descriptors = (0..5).map {
            val cipher = MessageCipher.fromId(buffer.get().toInt())
            val bits = (buffer.get().toInt() + 1) * 64
            Triple(cipher, bits, ByteArray(cipher.nonceBytes).also(buffer::get))
        }
        fun layer(index: Int, encrypt: Boolean, bytes: ByteArray): ByteArray {
            val (cipher, bits, nonce) = descriptors[index]
            val identity = byteArrayOf(index.toByte(), 6, cipher.id.toByte(), (bits / 64 - 1).toByte())
            val key = MessageKeys.expand(root, "layer-key", transcript, identity, bytes = bits / 8)
            return try { MessagePrimitives.aead(encrypt, cipher, key, nonce,
                fields(domain("layer-aad"), transcript, identity), bytes) } finally { key.fill(0) }
        }
        var body = sealed.copyOfRange(44 + length, sealed.size)
        try {
            for (index in 5 downTo damaged + 1) {
                val next = layer(index, false, body); body.fill(0); body = next
            }
            body[body.lastIndex] = (body.last().toInt() xor 1).toByte()
            for (index in damaged + 1..5) {
                val next = layer(index, true, body); body.fill(0); body = next
            }
            return sealed.copyOfRange(0, 44 + length) + body
        } finally { body.fill(0); root.fill(0); metadataKey.fill(0); metadata.fill(0); transcript.fill(0); binding.fill(0) }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.isEmpty()) listOf(emptyList())
        else values.flatMap { first -> permutations(values - first).map { listOf(first) + it } }
}
