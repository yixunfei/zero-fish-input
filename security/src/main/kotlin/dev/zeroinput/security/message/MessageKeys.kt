package dev.zeroinput.security.message

import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters

internal fun fields(vararg values: ByteArray): ByteArray = ByteBuffer.allocate(values.sumOf { 4 + it.size }).apply {
    values.forEach { putInt(it.size); put(it) }
}.array()

internal fun domain(value: String): ByteArray = "ZeroInput/message/v1/$value".toByteArray(Charsets.US_ASCII)
internal fun hash(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

internal object MessageKeys {
    fun expand(prk: ByteArray, purpose: String, vararg context: ByteArray, bytes: Int = 32): ByteArray {
        val info = fields(domain(purpose), *context)
        return try {
            ByteArray(bytes).also { output ->
                HKDFBytesGenerator(SHA256Digest()).apply {
                    init(HKDFParameters.skipExtractParameters(prk, info))
                }.generateBytes(output, 0, output.size)
            }
        } finally { info.fill(0) }
    }

    fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
    }

    fun root(credential: MessageCredential, salt: ByteArray, kdf: MessageKdf, minimumPasswordPoints: Int,
             derivePassword: (ByteArray, ByteArray, MessageKdf) -> ByteArray = ::argon): ByteArray =
        credential.withFactors { chars, file ->
            if (credential.mode.password != (kdf != MessageKdf.NONE)) throw MessageFailure()
            if (credential.mode.password &&
                Character.codePointCount(chars, 0, chars.size) < minimumPasswordPoints) throw MessageFailure()
            val text = utf8(chars, MessageLimits.CREDENTIAL_BYTES)
            var factor: ByteArray? = null
            var root: ByteArray? = null
            try {
                factor = when (credential.mode) {
                    MessageCredentialMode.PASSPHRASE -> text.copyOf()
                    MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD -> {
                        val input = fields(domain("local-biometric-password"), salt, text)
                        try { credential.authenticateLocal(input) } finally { input.fill(0) }
                    }
                    MessageCredentialMode.PASSPHRASE_FILE -> {
                        val input = fields(domain("passphrase-file"), text)
                        try { hmac(requireNotNull(file), input) } finally { input.fill(0) }
                    }
                    MessageCredentialMode.RANDOM_KEY -> decodeRandom(text)
                    MessageCredentialMode.RANDOM_KEY_FILE -> {
                        val random = decodeRandom(text)
                        val input = fields(domain("random-key-file"), random)
                        try { hmac(requireNotNull(file), input) } finally { random.fill(0); input.fill(0) }
                    }
                    MessageCredentialMode.KEY_FILE -> {
                        if (text.isNotEmpty()) throw MessageFailure()
                        requireNotNull(file).copyOf()
                    }
                }
                root = if (credential.mode.password) derivePassword(factor, salt, kdf) else factor.copyOf()
                hmac(salt, root)
            } finally { text.fill(0); factor?.fill(0); root?.fill(0) }
        }

    fun argon(input: ByteArray, salt: ByteArray, profile: MessageKdf): ByteArray {
        if (profile == MessageKdf.NONE) throw MessageFailure()
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
            .withMemoryAsKB(profile.memoryKiB).withIterations(profile.iterations).withParallelism(1).build()
        return try {
            ByteArray(32).also { output -> Argon2BytesGenerator().apply { init(params) }.generateBytes(input, output) }
        } finally { params.clear() }
    }

    private fun decodeRandom(value: ByteArray): ByteArray {
        if (value.size !in 43..683 || value.any { it.toInt().toChar() !in
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" }) throw MessageFailure()
        val result = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { throw MessageFailure() }
        if (result.size !in 32..512 || !Base64.getUrlEncoder().withoutPadding().encode(result).contentEquals(value)) {
            result.fill(0); throw MessageFailure()
        }
        return result
    }
}

/** Hashes exactly one bounded stream; the caller owns URI validation and closes the stream. */
object MessageKeyFile {
    /** Caller owns and clears these bytes. They are independent of all message and device keys. */
    fun generate(): ByteArray = ByteArray(32).also(java.security.SecureRandom()::nextBytes)

    fun factor(input: InputStream, declaredSize: Long? = null, isCurrent: () -> Boolean = { true }): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var length = 0L
        try {
            while (true) {
                if (!isCurrent()) throw MessageFailure()
                val count = input.read(buffer, 0, minOf(buffer.size, MessageLimits.KEY_FILE_BYTES + 1 - length.toInt()))
                if (count < 0) break
                if (count == 0) throw MessageFailure()
                length += count
                if (length > MessageLimits.KEY_FILE_BYTES) throw MessageFailure()
                digest.update(buffer, 0, count)
                buffer.fill(0)
            }
            if (length < 32 || declaredSize != null && declaredSize >= 0 && declaredSize != length || !isCurrent())
                throw MessageFailure()
            val hashed = digest.digest()
            val context = fields(domain("key-file"), ByteBuffer.allocate(8).putLong(length).array(), hashed)
            return try { hash(context) } finally { hashed.fill(0); context.fill(0) }
        } finally { buffer.fill(0); digest.reset() }
    }
}
