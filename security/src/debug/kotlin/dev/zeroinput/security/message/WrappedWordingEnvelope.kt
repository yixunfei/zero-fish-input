package dev.zeroinput.security.message

import java.security.SecureRandom

/** Versioned Debug envelope. The credential wraps separately generated per-layer keys. */
object WrappedWordingEnvelope {
    const val MAX_BYTES = MessageEnvelope.MAX_WRAPPED_ENVELOPE_BYTES
    private val envelope = MessageEnvelope()

    fun size(payloadBytes: Int, stages: List<MessageStage>): Int = MessageEnvelope.wrappedSize(payloadBytes, stages)

    fun randomizeWidths(stages: List<MessageStage>): List<MessageStage> {
        val random = SecureRandom()
        return stages.map { stage ->
            val widths = stage.cipher.keyBits
            MessageStage(stage.cipher, widths[random.nextInt(widths.size)])
        }
    }

    fun inspect(input: ByteArray): MessageRequirements = envelope.inspect(input, wrappedKeys = true).also {
        requirePortable(it.mode)
    }

    fun seal(profileDigest: ByteArray, payload: ByteArray, credential: MessageCredential,
             stages: List<MessageStage>, kdf: MessageKdf): ByteArray {
        requirePortable(credential.mode)
        return binding(profileDigest).use { envelope.sealBoundPayload(it, payload, credential, stages, kdf) }
    }

    fun open(profileDigest: ByteArray, input: ByteArray, credential: MessageCredential, approvedKdf: MessageKdf): ByteArray {
        requirePortable(credential.mode)
        return binding(profileDigest).use {
            envelope.openBoundPayload(it, input, credential, expectedStage = null) { kdf -> kdf == approvedKdf }
        }
    }

    internal fun binding(profileDigest: ByteArray): MessageBinding {
        if (profileDigest.size != 32) throw MessageFailure()
        return MessageBinding(3, hash(fields(domain("natural-wording-wrapped"), profileDigest)),
            wrappedKeys = true, minimumPasswordPoints = 1)
    }

    private fun requirePortable(mode: MessageCredentialMode) {
        if (mode == MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD) throw MessageFailure()
    }
}
