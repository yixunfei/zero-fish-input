package dev.zeroinput.security.message

import java.util.Base64

/** Binary adapter for the fixed, public P2 interoperability fixture set. */
object VerseTestEnvelope {
    private const val COVER = "ZeroInput/host-verse/v1/a1790ee980dc97d76d1d5d90c37dfdfc0b50718bcc2a1f857dbb34e6cf66ff1a"
    private val stage = MessageStage(MessageCipher.AES, 256)
    private val envelope = MessageEnvelope()

    fun seal(payload: ByteArray, key: ByteArray): ByteArray = withCredential(key) { credential ->
        envelope.sealPayload(COVER.toCharArray(), payload, credential, listOf(stage), MessageKdf.NONE)
    }

    fun open(input: ByteArray, key: ByteArray): ByteArray {
        if (input.size !in 162..2210 || (input.size - 98) % 64 != 0 ||
            input[16] != 2.toByte() || input[17] != 0.toByte() ||
            input[18] != 0.toByte() || input[19] != 38.toByte()) throw MessageFailure()
        return withCredential(key) { credential ->
            envelope.openPayload(COVER.toCharArray(), input, credential, expectedStage = stage) {
                it == MessageKdf.NONE
            }
        }
    }

    private inline fun <T> withCredential(key: ByteArray, use: (MessageCredential) -> T): T {
        if (key.size != 32) throw MessageFailure()
        val encoded = Base64.getUrlEncoder().withoutPadding().encode(key)
        val text = CharArray(encoded.size) { encoded[it].toInt().toChar() }
        return try {
            MessageCredential(MessageCredentialMode.RANDOM_KEY, text).use(use)
        } finally {
            text.fill('\u0000')
            encoded.fill(0)
        }
    }
}
