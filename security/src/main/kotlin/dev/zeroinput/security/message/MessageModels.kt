package dev.zeroinput.security.message

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

enum class MessageCipher(val id: Int, val keyBits: List<Int>, val nonceBytes: Int) {
    AES(1, listOf(128, 192, 256), 12),
    XCHACHA20(2, listOf(256), 24),
    KUZNYECHIK(3, listOf(256), 12),
    TWOFISH(4, listOf(128, 192, 256), 12),
    CAMELLIA(5, listOf(128, 192, 256), 12),
    SM4(6, listOf(128), 12);

    companion object {
        fun fromId(id: Int): MessageCipher = entries.singleOrNull { it.id == id } ?: throw MessageFailure()
    }
}

data class MessageStage(val cipher: MessageCipher, val bits: Int = cipher.keyBits.last()) {
    init { require(bits in cipher.keyBits) { "Unsupported message key width" } }
}

enum class MessageKdf(val id: Int, val memoryKiB: Int, val iterations: Int) {
    NONE(0, 0, 0), STANDARD(1, 65_536, 3), INCREASED(2, 131_072, 3), HIGH_MEMORY(3, 262_144, 4);

    companion object {
        fun fromId(id: Int): MessageKdf = entries.singleOrNull { it.id == id } ?: throw MessageFailure()
    }
}

enum class MessageCredentialMode(val id: Int, val password: Boolean, val file: Boolean) {
    PASSPHRASE(1, true, false), RANDOM_KEY(2, false, false), KEY_FILE(3, false, true),
    PASSPHRASE_FILE(4, true, true), RANDOM_KEY_FILE(5, false, true);

    companion object {
        fun fromId(id: Int): MessageCredentialMode = entries.singleOrNull { it.id == id } ?: throw MessageFailure()
    }
}

/** Owned transient factors. Callers retain no reference to its private copies. */
class MessageCredential(val mode: MessageCredentialMode, text: CharArray, fileFactor: ByteArray? = null) : AutoCloseable {
    private val value = text.copyOf()
    private val file = fileFactor?.copyOf()
    private var closed = false

    internal fun <T> withFactors(block: (CharArray, ByteArray?) -> T): T {
        if (closed || mode.file != (file != null) || file != null && file.size != 32) throw MessageFailure()
        return block(value, file)
    }

    override fun close() { closed = true; value.fill('\u0000'); file?.fill(0) }
}

/** Deliberately carries no content or provider error in its diagnostic text. */
class MessageFailure : Exception("Message operation failed")

object MessageLimits {
    const val SECRET_BYTES = 2048
    const val COVER_UNITS = 512
    const val COVER_BYTES = 1536
    const val CREDENTIAL_BYTES = 1024
    const val FRAME_BYTES = 2112
    const val ENVELOPE_BYTES = 2372
    const val KEY_FILE_BYTES = 1_048_576
}

internal fun utf8(value: CharArray, max: Int): ByteArray {
    if (value.size > max || value.any { it == '\u0000' }) throw MessageFailure()
    val bytes = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
    } catch (_: java.nio.charset.CharacterCodingException) { throw MessageFailure() }
    return try {
        if (bytes.remaining() > max) throw MessageFailure()
        ByteArray(bytes.remaining()).also(bytes::get)
    } finally { if (bytes.hasArray()) bytes.array().fill(0) }
}

internal fun utf8Chars(value: ByteArray): CharArray {
    val chars = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value))
    } catch (_: java.nio.charset.CharacterCodingException) { throw MessageFailure() }
    return try {
        CharArray(chars.remaining()).also(chars::get).also {
            if (it.any { ch -> ch == '\u0000' }) { it.fill('\u0000'); throw MessageFailure() }
        }
    } finally { if (chars.hasArray()) chars.array().fill('\u0000') }
}
