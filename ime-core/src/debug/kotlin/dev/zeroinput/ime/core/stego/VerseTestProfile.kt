package dev.zeroinput.ime.core.stego

import java.security.MessageDigest

class VerseTestFormatFailure : Exception("Invalid public verse test data")
class VerseTestCapacityFailure : Exception("Public verse test data exceeds capacity")

internal fun verifyVerseAsset(bytes: ByteArray, length: Int, sha256: String) {
    if (bytes.size != length) throw VerseTestFormatFailure()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (digest != sha256) throw VerseTestFormatFailure()
}
