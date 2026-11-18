package dev.zeroinput.security.message

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

data class MessageRequirements(val mode: MessageCredentialMode, val kdf: MessageKdf)

/** Independent portable v1 format. It neither opens a Keystore nor persists credentials. */
class MessageEnvelope internal constructor(
    private val random: SecureRandom,
    private val passwordKdf: (ByteArray, ByteArray, MessageKdf) -> ByteArray,
) {
    constructor() : this(SecureRandom(), MessageKeys::argon)

    fun inspect(envelope: ByteArray): MessageRequirements = bootstrap(envelope).let { MessageRequirements(it.mode, it.kdf) }

    fun seal(cover: CharArray, secret: CharArray, credential: MessageCredential,
             stages: List<MessageStage> = listOf(MessageStage(MessageCipher.AES)),
             kdf: MessageKdf = MessageKdf.STANDARD): ByteArray {
        validateStages(stages)
        val binding = binding(cover)
        var text: ByteArray? = null
        var prk: ByteArray? = null
        var frame: ByteArray? = null
        var output: ByteArray? = null
        try {
            text = utf8(secret, MessageLimits.SECRET_BYTES)
            if (text.isEmpty() || credential.mode.password != (kdf != MessageKdf.NONE)) throw MessageFailure()
            val frameLength = ((text.size + 36 + 63) / 64) * 64
            val descriptors = stages.map { StageNonce(it, fresh(it.cipher.nonceBytes)) }
            val metadata = metadata(descriptors, frameLength)
            val header = ByteBuffer.allocate(44).apply {
                put(fresh(16)); put(credential.mode.id.toByte()); put(kdf.id.toByte())
                putShort((metadata.size + 16).toShort()); put(fresh(24))
            }.array()
            val bootstrap = bootstrapForSeal(header)
            prk = MessageKeys.root(credential, bootstrap.salt, kdf, encrypt = true, passwordKdf)
            frame = fresh(frameLength)
            ByteBuffer.wrap(frame).apply { putInt(text.size); put(binding); put(text) }
            val encryptedMetadata = metadataCrypt(true, prk, bootstrap, binding, metadata)
            val transcript = transcript(header, metadata, binding)
            val body = cascade(true, prk, transcript, descriptors, frame)
            output = header + encryptedMetadata + body
            body.fill(0)
            val recovered = openWithRoot(cover, output, prk)
            try {
                if (!recovered.contentEquals(secret)) throw MessageFailure()
            } finally { recovered.fill('\u0000') }
            return output.also { output = null }
        } finally { binding.fill(0); text?.fill(0); frame?.fill(0); prk?.fill(0); output?.fill(0) }
    }

    fun open(cover: CharArray, envelope: ByteArray, credential: MessageCredential,
             approveCost: (MessageKdf) -> Boolean = { it == MessageKdf.STANDARD || it == MessageKdf.NONE }): CharArray {
        val header = bootstrap(envelope)
        if (credential.mode != header.mode || !approveCost(header.kdf)) throw MessageFailure()
        // Reject a malformed/oversize cover before allocating the password KDF.
        binding(cover).fill(0)
        val prk = MessageKeys.root(credential, header.salt, header.kdf, encrypt = false, passwordKdf)
        return try { openWithRoot(cover, envelope, prk) } finally { prk.fill(0) }
    }

    private fun openWithRoot(cover: CharArray, envelope: ByteArray, prk: ByteArray): CharArray {
        val header = bootstrap(envelope)
        val binding = binding(cover)
        var metadata: ByteArray? = null
        var frame: ByteArray? = null
        try {
            metadata = metadataCrypt(false, prk, header, binding, envelope.copyOfRange(44, 44 + header.metadataLength))
            val input = ByteBuffer.wrap(metadata)
            if (input.get().toInt() != 1) throw MessageFailure()
            val count = input.get().toInt() and 255
            if (count !in 1..6 || input.get().toInt() != 0 || input.get().toInt() != 1) throw MessageFailure()
            val frameLength = input.int
            if (frameLength !in 64..MessageLimits.FRAME_BYTES || frameLength % 64 != 0) throw MessageFailure()
            val descriptors = (0 until count).map {
                if (input.remaining() < 2) throw MessageFailure()
                val cipher = MessageCipher.fromId(input.get().toInt() and 255)
                val bits = when (input.get().toInt()) { 1 -> 128; 2 -> 192; 3 -> 256; else -> throw MessageFailure() }
                if (bits !in cipher.keyBits || input.remaining() < cipher.nonceBytes) throw MessageFailure()
                StageNonce(MessageStage(cipher, bits), ByteArray(cipher.nonceBytes).also(input::get))
            }
            validateStages(descriptors.map { it.stage })
            if (input.hasRemaining() || envelope.size != 44 + header.metadataLength + frameLength + 16 * count)
                throw MessageFailure()
            val transcript = transcript(header.bytes, metadata, binding)
            frame = cascade(false, prk, transcript, descriptors, envelope.copyOfRange(44 + header.metadataLength, envelope.size))
            val plain = ByteBuffer.wrap(frame)
            val length = plain.int
            val actualBinding = ByteArray(32).also(plain::get)
            if (length !in 1..MessageLimits.SECRET_BYTES || length > plain.remaining() ||
                ((length + 36 + 63) / 64) * 64 != frameLength || !MessageDigest.isEqual(binding, actualBinding))
                throw MessageFailure()
            val text = ByteArray(length).also(plain::get)
            return try { utf8Chars(text) } finally { text.fill(0) }
        } catch (_: java.nio.BufferUnderflowException) {
            throw MessageFailure()
        } finally { binding.fill(0); metadata?.fill(0); frame?.fill(0) }
    }

    private fun cascade(encrypt: Boolean, prk: ByteArray, transcript: ByteArray,
                        stages: List<StageNonce>, input: ByteArray): ByteArray {
        var current = input.copyOf()
        try {
            val indices = if (encrypt) stages.indices.toList() else stages.indices.reversed()
            for (index in indices) {
                val descriptor = stages[index]
                val stage = descriptor.stage
                val identity = ByteBuffer.allocate(4).put(index.toByte()).put(stages.size.toByte())
                    .put(stage.cipher.id.toByte()).put((stage.bits / 64 - 1).toByte()).array()
                val key = MessageKeys.expand(prk, "layer-key", transcript, identity, bytes = stage.bits / 8)
                val aad = fields(domain("layer-aad"), transcript, identity)
                val next = try {
                    MessagePrimitives.aead(encrypt, stage.cipher, key, descriptor.nonce, aad, current)
                } finally { key.fill(0); aad.fill(0) }
                current.fill(0)
                current = next
            }
            return current.also { current = ByteArray(0) }
        } finally { current.fill(0) }
    }

    private fun metadataCrypt(encrypt: Boolean, prk: ByteArray, header: Bootstrap,
                              binding: ByteArray, input: ByteArray): ByteArray {
        val key = MessageKeys.expand(prk, "metadata-key", header.bytes, binding)
        val aad = fields(domain("metadata-aad"), header.bytes, binding)
        return try {
            MessagePrimitives.aead(encrypt, MessageCipher.XCHACHA20, key, header.nonce, aad, input)
        } finally { key.fill(0); aad.fill(0) }
    }

    private fun transcript(header: ByteArray, metadata: ByteArray, binding: ByteArray): ByteArray =
        hash(fields(domain("descriptor"), header, metadata, binding))

    private fun metadata(stages: List<StageNonce>, frameLength: Int): ByteArray =
        ByteBuffer.allocate(8 + stages.sumOf { 2 + it.nonce.size }).apply {
            put(1); put(stages.size.toByte()); put(0); put(1); putInt(frameLength)
            stages.forEach { put(it.stage.cipher.id.toByte()); put((it.stage.bits / 64 - 1).toByte()); put(it.nonce) }
        }.array()

    private fun binding(cover: CharArray): ByteArray {
        if (cover.size !in 1..MessageLimits.COVER_UNITS || cover.all(Char::isWhitespace)) throw MessageFailure()
        val bytes = utf8(cover, MessageLimits.COVER_BYTES)
        return try { hash(fields(domain("exact-cover"), bytes)) } finally { bytes.fill(0) }
    }

    private fun validateStages(stages: List<MessageStage>) {
        if (stages.size !in 1..6 || stages.map { it.cipher }.distinct().size != stages.size) throw MessageFailure()
    }

    private fun fresh(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private data class StageNonce(val stage: MessageStage, val nonce: ByteArray)
    private data class Bootstrap(val bytes: ByteArray, val salt: ByteArray, val mode: MessageCredentialMode,
                                 val kdf: MessageKdf, val metadataLength: Int, val nonce: ByteArray)

    private fun bootstrapForSeal(header: ByteArray): Bootstrap = parseHeader(header)

    private fun bootstrap(envelope: ByteArray): Bootstrap {
        if (envelope.size !in 162..MessageLimits.ENVELOPE_BYTES) throw MessageFailure()
        val header = parseHeader(envelope.copyOfRange(0, 44))
        if (envelope.size < 44 + header.metadataLength + 80) throw MessageFailure()
        return header
    }

    private fun parseHeader(header: ByteArray): Bootstrap {
        val input = ByteBuffer.wrap(header)
        val salt = ByteArray(16).also(input::get)
        val mode = MessageCredentialMode.fromId(input.get().toInt() and 255)
        val kdf = MessageKdf.fromId(input.get().toInt() and 255)
        val length = input.short.toInt() and 65535
        if (mode.password != (kdf != MessageKdf.NONE) || length !in 38..120) throw MessageFailure()
        return Bootstrap(header, salt, mode, kdf, length, ByteArray(24).also(input::get))
    }
}
