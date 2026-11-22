package dev.zeroinput.security.message

/** Single-suite binary envelope for the natural wording trial. */
object NaturalWordingEnvelope {
    private val stage = MessageStage(MessageCipher.AES, 256)
    private val envelope = MessageEnvelope()

    fun seal(profileDigest: ByteArray, payload: ByteArray, credential: MessageCredential): ByteArray {
        checkCredential(credential)
        return binding(profileDigest).use {
            envelope.sealBoundPayload(it, payload, credential, listOf(stage), MessageKdf.NONE)
        }
    }

    fun inspect(input: ByteArray): MessageRequirements {
        if (input.size !in 162..2210 || (input.size - 98) % 64 != 0 ||
            input[16] != MessageCredentialMode.RANDOM_KEY.id.toByte() || input[17] != 0.toByte() ||
            input[18] != 0.toByte() || input[19] != 38.toByte()) throw MessageFailure()
        return MessageRequirements(MessageCredentialMode.RANDOM_KEY, MessageKdf.NONE)
    }

    fun open(profileDigest: ByteArray, input: ByteArray, credential: MessageCredential): ByteArray {
        inspect(input)
        checkCredential(credential)
        return binding(profileDigest).use {
            envelope.openBoundPayload(it, input, credential, stage) { kdf -> kdf == MessageKdf.NONE }
        }
    }

    private fun checkCredential(credential: MessageCredential) {
        if (credential.mode != MessageCredentialMode.RANDOM_KEY) throw MessageFailure()
        // MessageKeys additionally checks the alphabet and canonical unpadded encoding.
        credential.withFactors { text, _ -> if (text.size != 43) throw MessageFailure() }
    }

    private fun binding(profileDigest: ByteArray): MessageBinding {
        if (profileDigest.size != 32) throw MessageFailure()
        return MessageBinding(2, hash(fields(domain("natural-wording-trial"), profileDigest)))
    }
}
