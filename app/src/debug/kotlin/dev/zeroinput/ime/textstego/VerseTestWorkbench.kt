package dev.zeroinput.ime.textstego

import android.content.res.AssetManager
import dev.zeroinput.ime.core.stego.VerseTestBody
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.security.message.VerseTestEnvelope

internal data class VerseTestCarrier(val text: String, val originalBytes: Int, val envelopeBytes: Int) {
    val utf8Bytes = text.toByteArray(Charsets.UTF_8).size
    val passesTarget = utf8Bytes <= 5 * originalBytes
}

/** Local workbench; it has no source of user text, credentials or editor connections. */
internal class VerseTestWorkbench(private val assets: AssetManager) {
    private val profile by lazy { VerseTestAssets(assets) }
    private val body get() = profile.body
    private val surface get() = profile.surface

    fun sample(): ByteArray = readVerseAsset(assets, "body.txt", 1856)
    fun hostSample(): String = readVerseAsset(assets, "host-carrier.txt", 3868).toString(Charsets.UTF_8)

    fun seal(raw: ByteArray): VerseTestCarrier {
        val packet = body.encode(raw)
        try {
            val check = body.decode(packet)
            try {
                if (!raw.contentEquals(check)) throw VerseTestFormatFailure()
            } finally { check.fill(0) }
            val size = VerseTestBody.envelopeBytes(packet.size)
            surface.checkCapacity(size)
            val envelope = withPublicKey { VerseTestEnvelope.seal(packet, it) }
            try {
                if (envelope.size != size) throw VerseTestFormatFailure()
                val carrier = surface.encode(envelope)
                val recovered = surface.decode(carrier)
                try {
                    if (!envelope.contentEquals(recovered)) throw VerseTestFormatFailure()
                } finally { recovered.fill(0) }
                return VerseTestCarrier(carrier, raw.size, size)
            } finally { envelope.fill(0) }
        } finally { packet.fill(0) }
    }

    fun open(carrier: String): Pair<VerseTestCarrier, ByteArray> {
        val ciphertext = surface.decode(carrier)
        try {
            val packet = withPublicKey { VerseTestEnvelope.open(ciphertext, it) }
            try {
                val original = body.decode(packet)
                return VerseTestCarrier(carrier, original.size, ciphertext.size) to original
            } finally { packet.fill(0) }
        } finally { ciphertext.fill(0) }
    }

    private inline fun <T> withPublicKey(work: (ByteArray) -> T): T {
        val fixtureKey = ByteArray(32) { it.toByte() }
        return try { work(fixtureKey) } finally { fixtureKey.fill(0) }
    }
}
