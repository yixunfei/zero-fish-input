package dev.zeroinput.security.message

import java.security.SecureRandom
import java.util.Base64

/** Random master material; HKDF still derives each cipher's supported key width. */
object MessageRandomKey {
    fun validBits(bits: Int): Boolean = bits in 256..4096 && bits % 8 == 0

    /** Call on a background worker. Caller owns and clears the returned characters. */
    fun generate(bits: Int): CharArray {
        if (!validBits(bits)) throw MessageFailure()
        val bytes = ByteArray(bits / 8)
        var encoded: ByteArray? = null
        return try {
            SecureRandom().nextBytes(bytes)
            val text = Base64.getUrlEncoder().withoutPadding().encode(bytes)
            encoded = text
            CharArray(text.size) { text[it].toInt().toChar() }
        } finally { bytes.fill(0); encoded?.fill(0) }
    }
}
