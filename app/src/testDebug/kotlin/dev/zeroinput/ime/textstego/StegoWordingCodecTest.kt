package dev.zeroinput.ime.textstego

import android.app.Application
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.ime.core.stego.VerseTestSurface
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageCredentialMode
import dev.zeroinput.security.message.MessageEnvelope
import dev.zeroinput.security.message.MessageFailure
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageRequirements
import dev.zeroinput.security.message.MessageStage
import dev.zeroinput.security.message.NaturalWordingEnvelope
import dev.zeroinput.security.message.WrappedWordingEnvelope
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StegoWordingCodecTest {
    private val assets get() = RuntimeEnvironment.getApplication().assets
    private val stages = listOf(MessageStage(MessageCipher.AES, 192), MessageStage(MessageCipher.XCHACHA20))

    @Test fun restoresExactUnicodeAndMeasuresTheCompleteCarrier() = key(67).use { key ->
        val body = "繁體、𠀋、🙂、e\u0301\r\n保留原换行\n"
        for (profile in StegoWordingProfile.entries) {
            val made = seal(body.toCharArray(), key, profile)
            assertEquals(body.toByteArray().size, made.originalBytes)
            assertEquals(made.text.toByteArray().size, made.utf8Bytes)
            assertEquals(profile != StegoWordingProfile.PROSE_WINDOW, made.text.contains('\n'))
            assertEquals(100.0 * body.toByteArray().size / made.text.toByteArray().size, made.utilizationPercent, 1e-9)
            assertTrue(made.utilizationPercent < 20)
            assertFalse(made.text.contains(StegoCarrierCodec.VISIBLE_START))
            assertFalse(made.text.contains(StegoCarrierCodec.DENSE_START))
            assertEquals(MessageRequirements(MessageCredentialMode.RANDOM_KEY, MessageKdf.NONE),
                StegoWordingCodec.inspect(assets, profile, made.text) { true })
            assertArrayEquals(body.toCharArray(), open(made.text, key, profile))
            assertNotEquals(made.text, seal(body.toCharArray(), key, profile).text)
        }
    }

    @Test fun receiverRejectsLegacyEnvelopesAndSeparatesCurrentProfiles(): Unit = key(0, increment = true).use { key ->
        val legacy = VerseTestAssets(assets)
        val frozen = VerseProfileAssets(assets)
        val body = "公开试用夹具"
        val packet = legacy.body.encode(body.toByteArray())
        val v1Envelope = NaturalWordingEnvelope.seal(legacy.digest, packet, key)
        val v1 = legacy.surface.encode(v1Envelope)
        val rejection = runCatching { open(v1, key) }.exceptionOrNull()
        assertTrue(rejection is VerseTestFormatFailure || rejection is MessageFailure)
        val cover = "public exact cover".toCharArray()
        val exact = MessageEnvelope().seal(cover, "public test".toCharArray(), key, kdf = MessageKdf.NONE)
        val made = seal(body.toCharArray(), key)
        val cipher = frozen.five.surface.decodeWrapped(made.text, WrappedWordingEnvelope.MAX_BYTES)
        assertThrows(MessageFailure::class.java) { MessageEnvelope().open(cover, cipher, key) }
        val seven = frozen.seven.surface.encodeWrapped(cipher, WrappedWordingEnvelope.MAX_BYTES)
        assertThrows(MessageFailure::class.java) { open(seven, key, StegoWordingProfile.SEVEN_VERSE) }
        val window = frozen.window.surface.operation { true }.encode(cipher)
        assertThrows(MessageFailure::class.java) { open(window, key, StegoWordingProfile.PROSE_WINDOW) }
        key(0, bytes = 64).use { wrongWidth ->
            assertThrows(MessageFailure::class.java) { NaturalWordingEnvelope.open(legacy.digest, v1Envelope, wrongWidth) }
        }
    }

    @Test fun wrongKeyEditedLayoutAndCanonicalTamperedCiphertextFailClosed(): Unit = key(5).use { key ->
        val made = seal("公开测试".toCharArray(), key)
        key(6).use { wrong ->
            assertThrows(MessageFailure::class.java) { open(made.text, wrong) }
        }
        val altered = made.text.replaceFirst("\n", "\r\n")
        assertThrows(VerseTestFormatFailure::class.java) { open(altered, key) }
        assertThrows(VerseTestFormatFailure::class.java) {
            StegoWordingCodec.inspect(assets, StegoWordingProfile.VERSE, altered) { true }
        }
        val surface = VerseProfileAssets(assets).five.surface
        val max = WrappedWordingEnvelope.MAX_BYTES
        val bytes = surface.decodeWrapped(made.text, max)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(MessageFailure::class.java) { open(surface.encodeWrapped(bytes, max), key) }
        assertThrows(MessageFailure::class.java) {
            StegoWordingCodec.open(assets, StegoWordingProfile.VERSE, made.text, key, { false }) { true }
        }
    }

    @Test fun olderBindingsAndAuthenticatedGbBodyAreRejected(): Unit = key(18).use { key ->
        val frozen = VerseProfileAssets(assets)
        val original = "公开的旧格式样本𠀋🙂\r\n".toByteArray()
        val gb = VerseTestAssets(assets).body.encode(original)
        val words = frozen.wordBody.encode(original, { WrappedWordingEnvelope.size(it, stages) }) { true }
        val older = profileFingerprint("v2/verse",
            sha256(readVerseAsset(assets, "opening.bin", VerseTestSurface.Form.FIVE.assetBytes)),
            sha256(readVerseAsset(assets, "gb-map.bin", 50_364)),
            sha256(readVerseAsset(assets, "gb18030.zdict", 15_021)))
        val max = WrappedWordingEnvelope.MAX_BYTES
        fun render(digest: ByteArray, packet: ByteArray): String {
            val sealed = WrappedWordingEnvelope.seal(digest, packet, key, stages, MessageKdf.NONE)
            return frozen.five.surface.encodeWrapped(sealed, max)
        }

        for (packet in listOf(gb, words))
            assertThrows(MessageFailure::class.java) { open(render(older, packet), key) }
        assertThrows(VerseTestFormatFailure::class.java) { open(render(frozen.five.digest, gb), key) }
    }

    @Test fun nearLimitUtf8DraftCanExceedTheVerseCapacityAfterCompression(): Unit = key(5).use { key ->
        val random = java.util.Random(8177)
        val difficult = CharArray(682) { (0x800 + random.nextInt(0x4e00 - 0x800)).toChar() }
        assertEquals(2046, String(difficult).toByteArray().size)
        for (profile in StegoWordingProfile.entries)
            assertThrows(StegoWordingCapacityFailure::class.java) { seal(difficult, key, profile) }
    }

    @Test fun sevenCharacterVerseHasCanonicalPunctuationAndRejectsTruncation(): Unit = key(9).use { key ->
        val profile = StegoWordingProfile.SEVEN_VERSE
        val text = seal("公开的七言试用文本".toCharArray(), key, profile).text
        val lines = text.split('，', '。', '\n').filter { it.isNotEmpty() }
        assertTrue(lines.size > 4 && lines.all { it.length == 7 })
        assertThrows(VerseTestFormatFailure::class.java) { open(text.dropLast(1), key, profile) }
        assertThrows(VerseTestFormatFailure::class.java) { open(text.replaceFirst("，", "。"), key, profile) }
    }

    @Test fun bothVerseSurfacesStopWhenRevokedDuringRankingOrValidation() {
        val frozen = VerseProfileAssets(assets)
        val packet = ByteArray(524) { it.toByte() }
        val max = WrappedWordingEnvelope.MAX_BYTES
        for (verse in listOf(frozen.five.surface, frozen.seven.surface)) {
            val carrier = verse.encodeWrapped(packet, max)
            assertArrayEquals(packet, verse.decodeWrapped(carrier, max))
            var checks = 0
            assertThrows(VerseTestFormatFailure::class.java) { verse.encodeWrapped(packet, max) { ++checks < 2 } }
            checks = 0
            assertThrows(VerseTestFormatFailure::class.java) { verse.decodeWrapped(carrier, max) { ++checks < 2 } }
            val lines = carrier.count { it == '，' || it == '。' }
            val parsingChecks = (lines + 31) / 32 + 1
            checks = 0
            assertThrows(VerseTestFormatFailure::class.java) {
                verse.decodeWrapped(carrier, max) { ++checks <= parsingChecks }
            }
            assertEquals(parsingChecks + 1, checks)
        }
    }

    private fun seal(body: CharArray, key: MessageCredential, profile: StegoWordingProfile = StegoWordingProfile.VERSE) =
        StegoWordingCodec.seal(assets, profile, body, key, stages, MessageKdf.NONE, false) { true }

    private fun open(text: String, key: MessageCredential, profile: StegoWordingProfile = StegoWordingProfile.VERSE) =
        StegoWordingCodec.open(assets, profile, text, key, { true }) { true }

    private fun key(value: Int, bytes: Int = 32, increment: Boolean = false) = MessageCredential(
        MessageCredentialMode.RANDOM_KEY,
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) {
            (value + if (increment) it else 0).toByte()
        }).toCharArray(),
    )
}
