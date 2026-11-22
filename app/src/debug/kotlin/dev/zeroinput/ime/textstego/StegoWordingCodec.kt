package dev.zeroinput.ime.textstego

import android.content.res.AssetManager
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.VerseTestCapacityFailure
import dev.zeroinput.ime.core.stego.VerseTestSurface
import dev.zeroinput.ime.core.stego.WordContextBody
import dev.zeroinput.ime.ui.StegoField
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageFailure
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageRequirements
import dev.zeroinput.security.message.MessageStage
import dev.zeroinput.security.message.WrappedWordingEnvelope
import java.nio.ByteBuffer
import java.nio.CharBuffer

internal object StegoWordingCodec {
    const val available = true
    private var profile: VerseProfileAssets? = null

    @Synchronized private fun profile(assets: AssetManager): VerseProfileAssets =
        profile ?: VerseProfileAssets(assets).also { profile = it }

    fun seal(assets: AssetManager, selected: StegoWordingProfile, secret: CharArray, key: MessageCredential,
             stages: List<MessageStage>, kdf: MessageKdf, randomWidths: Boolean,
             current: () -> Boolean): StegoWordingCarrier {
        if (!current()) throw MessageFailure()
        val input = CharBuffer.wrap(secret)
        if (!StegoPastePolicy.accepts(StegoField.SECRET, input)) throw MessageFailure()
        val utf8 = Charsets.UTF_8.newEncoder().encode(input)
        try {
            val original = ByteArray(utf8.remaining()).also(utf8::get)
            return try {
                val frozen = profile(assets)
                if (!current()) throw MessageFailure()
                val selectedStages = if (randomWidths) WrappedWordingEnvelope.randomizeWidths(stages) else stages
                seal(frozen.wordBody, surface(frozen, selected, current), original, key, selectedStages, kdf, current)
            } finally { original.fill(0) }
        } catch (_: VerseTestCapacityFailure) {
            throw StegoWordingCapacityFailure()
        } finally { if (utf8.hasArray()) utf8.array().fill(0) }
    }

    private fun seal(body: WordContextBody, surface: Surface, original: ByteArray, key: MessageCredential,
                     stages: List<MessageStage>, kdf: MessageKdf,
                     current: () -> Boolean): StegoWordingCarrier {
        val packet = body.encode(original, { WrappedWordingEnvelope.size(it, stages) }, current)
        try {
            val checked = body.decode(packet, current)
            try { if (!checked.contentEquals(original)) throw MessageFailure() } finally { checked.fill(0) }
            val size = WrappedWordingEnvelope.size(packet.size, stages)
            surface.checkCapacity(size)
            if (!current()) throw MessageFailure()
            val envelope = WrappedWordingEnvelope.seal(surface.digest, packet, key, stages, kdf)
            try {
                if (envelope.size != size) throw MessageFailure()
                if (!current()) throw MessageFailure()
                val carrier = surface.encode(envelope)
                val utf8Bytes = StegoCarrierCodec.utf8Length(carrier)
                if (utf8Bytes > StegoCarrierCodec.MAX_BYTES) throw StegoWordingCapacityFailure()
                val recovered = surface.decode(carrier)
                try { if (!recovered.contentEquals(envelope)) throw MessageFailure() } finally { recovered.fill(0) }
                return StegoWordingCarrier(carrier, original.size, utf8Bytes)
            } finally { envelope.fill(0) }
        } finally { packet.fill(0) }
    }

    fun inspect(assets: AssetManager, selected: StegoWordingProfile, carrier: String,
                current: () -> Boolean): MessageRequirements =
        withCarrier(assets, selected, carrier, current) { _, _, envelope -> WrappedWordingEnvelope.inspect(envelope) }

    fun open(assets: AssetManager, selected: StegoWordingProfile, carrier: String, key: MessageCredential,
             current: () -> Boolean, approve: (MessageRequirements) -> Boolean): CharArray? =
        withCarrier(assets, selected, carrier, current) { frozen, surface, envelope ->
            val requirements = WrappedWordingEnvelope.inspect(envelope)
            if (!approve(requirements)) return@withCarrier null
            if (!current()) throw MessageFailure()
            val packet = WrappedWordingEnvelope.open(surface.digest, envelope, key, requirements.kdf)
            try {
                if (!current()) throw MessageFailure()
                val original = frozen.wordBody.decode(packet, current)
                try { originalChars(original, current) } finally { original.fill(0) }
            } finally { packet.fill(0) }
        }

    private fun <T> withCarrier(assets: AssetManager, selected: StegoWordingProfile, carrier: String,
                                current: () -> Boolean, action: (VerseProfileAssets, Surface, ByteArray) -> T): T {
        if (!current() || !StegoPastePolicy.accepts(StegoField.CARRIER, carrier)) throw MessageFailure()
        val frozen = profile(assets)
        if (!current()) throw MessageFailure()
        val surface = surface(frozen, selected, current)
        val envelope = surface.decode(carrier)
        return try {
            if (!current()) throw MessageFailure()
            action(frozen, surface, envelope)
        } finally { envelope.fill(0) }
    }

    private fun surface(frozen: VerseProfileAssets, selected: StegoWordingProfile, current: () -> Boolean): Surface {
        return when (selected) {
            StegoWordingProfile.VERSE -> verseSurface(frozen.five, current)
            StegoWordingProfile.SEVEN_VERSE -> verseSurface(frozen.seven, current)
            StegoWordingProfile.PROSE_WINDOW -> frozen.window.let { window ->
                val operation = window.surface.operation(current)
                Surface(window.digest, operation::checkCapacity, operation::encode, operation::decode)
            }
        }
    }

    private fun verseSurface(profile: VerseProfileAssets.Profile<VerseTestSurface>, current: () -> Boolean): Surface {
        val max = WrappedWordingEnvelope.MAX_BYTES
        val verse = profile.surface
        return Surface(profile.digest, { verse.checkWrappedCapacity(it, max) },
            { verse.encodeWrapped(it, max, current) }, { verse.decodeWrapped(it, max, current) })
    }

    private class Surface(val digest: ByteArray, val checkCapacity: (Int) -> Unit,
                          val encode: (ByteArray) -> String, val decode: (String) -> ByteArray)

    private fun originalChars(original: ByteArray, current: () -> Boolean): CharArray {
        val source = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original))
        try {
            if (!current() || !StegoPastePolicy.accepts(StegoField.SECRET, source)) throw MessageFailure()
            return CharArray(source.remaining()).also(source::get)
        } finally { if (source.hasArray()) source.array().fill('\u0000') }
    }
}
