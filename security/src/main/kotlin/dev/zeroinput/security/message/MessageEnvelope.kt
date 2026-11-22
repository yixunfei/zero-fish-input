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

    fun inspect(envelope: ByteArray): MessageRequirements = inspect(envelope, wrappedKeys = false)

    internal fun inspect(envelope: ByteArray, wrappedKeys: Boolean): MessageRequirements =
        bootstrap(envelope, wrappedKeys).let { MessageRequirements(it.mode, it.kdf) }

    fun seal(cover: CharArray, secret: CharArray, credential: MessageCredential,
             stages: List<MessageStage> = listOf(MessageStage(MessageCipher.AES)),
             kdf: MessageKdf = MessageKdf.STANDARD): ByteArray {
        val text = utf8(secret, MessageLimits.SECRET_BYTES)
        return try { sealPayload(cover, text, credential, stages, kdf) } finally { text.fill(0) }
    }

    internal fun sealPayload(cover: CharArray, payload: ByteArray, credential: MessageCredential,
                             stages: List<MessageStage>, kdf: MessageKdf): ByteArray =
        exactBinding(cover).use { sealBoundPayload(it, payload, credential, stages, kdf) }

    internal fun sealBoundPayload(binding: MessageBinding, payload: ByteArray, credential: MessageCredential,
                                  stages: List<MessageStage>, kdf: MessageKdf): ByteArray {
        validateStages(stages)
        var prk: ByteArray? = null
        var frame: ByteArray? = null
        var output: ByteArray? = null
        var metadata: ByteArray? = null
        val descriptors = ArrayList<Layer>(stages.size)
        try {
            if (credential.mode.password != (kdf != MessageKdf.NONE)) throw MessageFailure()
            val frameLength = frameBytes(payload.size)
            for (stage in stages) {
                val nonce = fresh(stage.cipher.nonceBytes)
                val key = if (binding.wrappedKeys) fresh(stage.bits / 8) else null
                descriptors.add(Layer(stage, nonce, key))
            }
            metadata = metadata(descriptors, frameLength, binding.version)
            val header = ByteBuffer.allocate(HEADER_BYTES).apply {
                put(fresh(16)); put(credential.mode.id.toByte()); put(kdf.id.toByte())
                putShort((metadata.size + 16).toShort()); put(fresh(24))
            }.array()
            val bootstrap = bootstrapForSeal(header)
            prk = MessageKeys.root(credential, bootstrap.salt, kdf, binding.minimumPasswordPoints, passwordKdf)
            frame = fresh(frameLength)
            ByteBuffer.wrap(frame).apply { putInt(payload.size); put(binding.digest); put(payload) }
            val encryptedMetadata = metadataCrypt(true, prk, bootstrap, binding.digest, metadata)
            val transcript = transcript(header, metadata, binding.digest)
            val body = try { cascade(true, prk, transcript, descriptors, frame) } finally { transcript.fill(0) }
            output = try { header + encryptedMetadata + body } finally { body.fill(0) }
            val recovered = openPayloadWithRoot(binding, output, prk, expectedStage = null)
            try {
                if (!MessageDigest.isEqual(recovered, payload)) throw MessageFailure()
            } finally { recovered.fill(0) }
            return output.also { output = null }
        } finally {
            frame?.fill(0); prk?.fill(0); output?.fill(0); metadata?.fill(0)
            descriptors.forEach(Layer::clear)
        }
    }

    fun open(cover: CharArray, envelope: ByteArray, credential: MessageCredential,
             approveCost: (MessageKdf) -> Boolean = { it == MessageKdf.STANDARD || it == MessageKdf.NONE }): CharArray {
        val payload = openPayload(cover, envelope, credential, approveCost = approveCost)
        return try { utf8Chars(payload) } finally { payload.fill(0) }
    }

    internal fun openPayload(cover: CharArray, envelope: ByteArray, credential: MessageCredential,
                             expectedStage: MessageStage? = null, approveCost: (MessageKdf) -> Boolean): ByteArray =
        exactBinding(cover).use { openBoundPayload(it, envelope, credential, expectedStage, approveCost) }

    internal fun openBoundPayload(binding: MessageBinding, envelope: ByteArray, credential: MessageCredential,
                                  expectedStage: MessageStage?, approveCost: (MessageKdf) -> Boolean): ByteArray {
        val header = bootstrap(envelope, binding.wrappedKeys)
        if (credential.mode != header.mode || !approveCost(header.kdf)) throw MessageFailure()
        val prk = MessageKeys.root(credential, header.salt, header.kdf, 1, passwordKdf)
        return try { openPayloadWithRoot(binding, envelope, prk, expectedStage) } finally { prk.fill(0) }
    }

    private fun openPayloadWithRoot(binding: MessageBinding, envelope: ByteArray, prk: ByteArray,
                                    expectedStage: MessageStage?): ByteArray {
        val header = bootstrap(envelope, binding.wrappedKeys)
        var metadata: ByteArray? = null
        var frame: ByteArray? = null
        val descriptors = ArrayList<Layer>(6)
        try {
            val bodyStart = HEADER_BYTES + header.metadataLength
            metadata = metadataCrypt(false, prk, header, binding.digest, envelope.copyOfRange(HEADER_BYTES, bodyStart))
            val input = ByteBuffer.wrap(metadata)
            if (input.get().toInt() != 1) throw MessageFailure()
            val count = input.get().toInt() and 255
            if (count !in 1..6 || input.get().toInt() != 0 || input.get() != binding.version) throw MessageFailure()
            val frameLength = input.int
            if (frameLength !in 64..MessageLimits.FRAME_BYTES || frameLength % 64 != 0) throw MessageFailure()
            repeat(count) { descriptors.add(readLayer(input, binding.wrappedKeys)) }
            validateStages(descriptors.map { it.stage })
            if (expectedStage != null && descriptors.singleOrNull()?.stage != expectedStage) throw MessageFailure()
            if (input.hasRemaining() || envelope.size != bodyStart + frameLength + TAG_BYTES * count)
                throw MessageFailure()
            val transcript = transcript(header.bytes, metadata, binding.digest)
            frame = try {
                cascade(false, prk, transcript, descriptors, envelope.copyOfRange(bodyStart, envelope.size))
            } finally { transcript.fill(0) }
            val plain = ByteBuffer.wrap(frame)
            val length = plain.int
            val actualBinding = ByteArray(32).also(plain::get)
            val matchesBinding = MessageDigest.isEqual(binding.digest, actualBinding)
            actualBinding.fill(0)
            if (length > plain.remaining() || frameBytes(length) != frameLength || !matchesBinding)
                throw MessageFailure()
            return ByteArray(length).also(plain::get)
        } catch (_: java.nio.BufferUnderflowException) {
            throw MessageFailure()
        } finally { metadata?.fill(0); frame?.fill(0); descriptors.forEach(Layer::clear) }
    }

    private fun readLayer(input: ByteBuffer, wrappedKeys: Boolean): Layer {
        val cipher = MessageCipher.fromId(input.get().toInt() and 255)
        val bits = when (input.get().toInt()) { 1 -> 128; 2 -> 192; 3 -> 256; else -> throw MessageFailure() }
        val keyBytes = if (wrappedKeys) bits / 8 else 0
        if (bits !in cipher.keyBits || input.remaining() < cipher.nonceBytes + keyBytes) throw MessageFailure()
        val nonce = ByteArray(cipher.nonceBytes).also(input::get)
        val key = if (wrappedKeys) ByteArray(keyBytes).also(input::get) else null
        return Layer(MessageStage(cipher, bits), nonce, key)
    }

    private fun cascade(encrypt: Boolean, prk: ByteArray, transcript: ByteArray,
                        stages: List<Layer>, input: ByteArray): ByteArray {
        var current = input.copyOf()
        try {
            val indices = if (encrypt) stages.indices.toList() else stages.indices.reversed()
            for (index in indices) {
                val descriptor = stages[index]
                val stage = descriptor.stage
                val identity = ByteBuffer.allocate(4).put(index.toByte()).put(stages.size.toByte())
                    .put(stage.cipher.id.toByte()).put((stage.bits / 64 - 1).toByte()).array()
                val key = descriptor.key ?: MessageKeys.expand(prk, "layer-key", transcript, identity, bytes = stage.bits / 8)
                val aad = fields(domain("layer-aad"), transcript, identity)
                val next = try {
                    MessagePrimitives.aead(encrypt, stage.cipher, key, descriptor.nonce, aad, current)
                } finally { if (descriptor.key == null) key.fill(0); aad.fill(0) }
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

    private fun transcript(header: ByteArray, metadata: ByteArray, binding: ByteArray): ByteArray {
        val input = fields(domain("descriptor"), header, metadata, binding)
        return try { hash(input) } finally { input.fill(0) }
    }

    private fun metadata(stages: List<Layer>, frameLength: Int, bindingVersion: Byte): ByteArray =
        ByteBuffer.allocate(8 + stages.sumOf { 2 + it.nonce.size + (it.key?.size ?: 0) }).apply {
            put(1); put(stages.size.toByte()); put(0); put(bindingVersion); putInt(frameLength)
            stages.forEach {
                put(it.stage.cipher.id.toByte()); put((it.stage.bits / 64 - 1).toByte()); put(it.nonce)
                it.key?.let { key -> put(key) }
            }
        }.array()

    private fun exactBinding(cover: CharArray): MessageBinding {
        if (cover.size !in 1..MessageLimits.COVER_UNITS || cover.all(Char::isWhitespace)) throw MessageFailure()
        val bytes = utf8(cover, MessageLimits.COVER_BYTES)
        val context = fields(domain("exact-cover"), bytes)
        return try { MessageBinding(1, hash(context)) } finally { bytes.fill(0); context.fill(0) }
    }

    private fun fresh(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private class Layer(val stage: MessageStage, val nonce: ByteArray, val key: ByteArray?) {
        fun clear() { key?.fill(0) }
    }
    private data class Bootstrap(val bytes: ByteArray, val salt: ByteArray, val mode: MessageCredentialMode,
                                 val kdf: MessageKdf, val metadataLength: Int, val nonce: ByteArray)

    private fun bootstrapForSeal(header: ByteArray): Bootstrap = parseHeader(header)

    private fun bootstrap(envelope: ByteArray, wrappedKeys: Boolean): Bootstrap {
        val limits = if (wrappedKeys) 178..MAX_WRAPPED_ENVELOPE_BYTES else 162..MessageLimits.ENVELOPE_BYTES
        if (envelope.size !in limits) throw MessageFailure()
        val header = parseHeader(envelope.copyOfRange(0, HEADER_BYTES))
        val length = header.metadataLength
        if (wrappedKeys) {
            val body = envelope.size - HEADER_BYTES - length
            if (length !in 54..296 || (1..6).none {
                    val frame = body - TAG_BYTES * it
                    frame in 64..MessageLimits.FRAME_BYTES && frame % 64 == 0
                }) throw MessageFailure()
        } else if (length !in 38..120 || envelope.size < HEADER_BYTES + length + 80) throw MessageFailure()
        return header
    }

    private fun parseHeader(header: ByteArray): Bootstrap {
        val input = ByteBuffer.wrap(header)
        val salt = ByteArray(16).also(input::get)
        val mode = MessageCredentialMode.fromId(input.get().toInt() and 255)
        val kdf = MessageKdf.fromId(input.get().toInt() and 255)
        val length = input.short.toInt() and 65535
        if (mode.password != (kdf != MessageKdf.NONE)) throw MessageFailure()
        return Bootstrap(header, salt, mode, kdf, length, ByteArray(24).also(input::get))
    }

    companion object {
        private const val HEADER_BYTES = 44
        private const val TAG_BYTES = 16
        internal const val MAX_WRAPPED_ENVELOPE_BYTES = MessageLimits.ENVELOPE_BYTES + 5 * 32 + 16

        internal fun wrappedSize(payloadBytes: Int, stages: List<MessageStage>): Int {
            validateStages(stages)
            return HEADER_BYTES + 8 + TAG_BYTES + frameBytes(payloadBytes) +
                stages.sumOf { 2 + it.cipher.nonceBytes + it.bits / 8 + TAG_BYTES }
        }

        private fun frameBytes(payloadBytes: Int): Int {
            if (payloadBytes !in 1..MessageLimits.SECRET_BYTES) throw MessageFailure()
            return ((payloadBytes + 36 + 63) / 64) * 64
        }

        private fun validateStages(stages: List<MessageStage>) {
            if (stages.size !in 1..6 || stages.map { it.cipher }.distinct().size != stages.size) throw MessageFailure()
        }
    }
}

internal class MessageBinding(
    val version: Byte,
    val digest: ByteArray,
    val wrappedKeys: Boolean = false,
    val minimumPasswordPoints: Int = 12,
) : AutoCloseable {
    override fun close() { digest.fill(0) }
}
