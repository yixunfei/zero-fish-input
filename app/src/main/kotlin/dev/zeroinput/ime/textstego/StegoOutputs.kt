package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.core.stego.StegoCarrierCodec

internal enum class StegoOutput { PLAINTEXT, COVER, CIPHERTEXT, CARRIER }

/** Owned results live only for the workspace's result window. No credentials are retained. */
internal class StegoOutputs(
    cover: CharArray?,
    plaintext: CharArray,
    envelope: ByteArray?,
    carrier: String,
) : AutoCloseable {
    private var carrier: String? = carrier
    private val cover = cover?.copyOf()
    private val plaintext = plaintext.copyOf()
    private val ciphertext = envelope?.let { StegoCarrierCodec.ciphertext(it).toCharArray() }

    fun copy(kind: StegoOutput): CharArray? {
        val complete = carrier ?: return null
        return when (kind) {
            StegoOutput.PLAINTEXT -> plaintext.copyOf()
            StegoOutput.COVER -> cover?.copyOf()
            StegoOutput.CIPHERTEXT -> ciphertext?.copyOf()
            StegoOutput.CARRIER -> complete.toCharArray()
        }
    }

    override fun close() { carrier = null; cover?.fill('\u0000'); plaintext.fill('\u0000'); ciphertext?.fill('\u0000') }
}
