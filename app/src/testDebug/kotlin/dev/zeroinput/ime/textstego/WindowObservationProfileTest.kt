package dev.zeroinput.ime.textstego

import android.app.Application
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.VerseTestCapacityFailure
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.ime.core.stego.WindowObservationGrammar
import dev.zeroinput.ime.core.stego.WindowObservationSurface
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageCredentialMode
import dev.zeroinput.security.message.MessageFailure
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageStage
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WindowObservationProfileTest {
    private val assets get() = RuntimeEnvironment.getApplication().assets

    @Test fun matchesFrozenHostVectorsThroughTheGuaranteedBoundary() {
        val window = VerseProfileAssets(assets).window
        assertEquals("4ffea5f4782af73075a051b8f71c767f489df12072ff436cee6479b6529e6de5", window.digest.hex())
        val vectors = listOf(
            Vector(1, false, 21, "404f1ca80cf5c92c8c81521d95d41d3598cb33a61a3a2eb1dfdfbe2cbdef4138"),
            Vector(178, false, 3507, "0475379487e49d3dd88fae9fbda0ae6876a3b613211f229e4132d9cb6ff7bbfb"),
            Vector(524, true, 10302, "0c4911555b453fe2e7663a590dc9bc42f92f4ee1d098777f1c4af8333f9631f0"),
            Vector(833, false, 16371, "33c1800b2aefdf215dbf68fa42b6d4c028671ddbf6a87603b8c7b124ad069f9e"),
            Vector(833, true, 16371, "da117073b518ce3da4833e3f3110aaa2dc50fbda1302fc3350ab78c612ce9ca5"),
        )
        for ((size, max, utf8Bytes, hash) in vectors) {
            val packet = ByteArray(size) { if (max) -1 else if (it < 5) 0 else (it * 67).toByte() }
            val operation = window.surface.operation { true }
            val text = operation.encode(packet)
            assertEquals(utf8Bytes, StegoCarrierCodec.utf8Length(text))
            assertEquals(hash, sha256(text.toByteArray()).hex())
            assertArrayEquals(packet, operation.decode(text))
        }
        val operation = window.surface.operation { true }
        val oversize = WindowObservationSurface.MAX_ENVELOPE_BYTES + 1
        assertThrows(VerseTestCapacityFailure::class.java) { operation.encode(ByteArray(oversize)) }
        assertThrows(VerseTestCapacityFailure::class.java) { operation.encode(ByteArray(oversize) { -1 }) }
    }

    @Test fun rejectsAnUnsafeGraphAVisibleEditAndCancelledCounting() {
        val asset = readVerseAsset(assets, WindowObservationGrammar.ASSET_NAME, WindowObservationGrammar.ASSET_BYTES)
        asset[asset.lastIndex] = (asset.last().toInt() xor 1).toByte()
        assertThrows(VerseTestFormatFailure::class.java) { WindowObservationGrammar(asset) }
        val graph = VerseProfileAssets(assets).window.surface
        val packet = ByteArray(524) { -1 }
        val text = graph.operation { true }.encode(packet)
        val introduction = "云影掠过石阶，"
        val originalPair = introduction + "只剩一层影子。"
        assertTrue(originalPair in text)
        val edited = listOf(text.replaceFirst("，", "。"), text.replaceFirst("。", "，"), text.dropLast(1), text.replaceFirst("。", "。\n"),
            text.replaceFirst(originalPair, introduction + "水波一动。"),
            text.substringBefore(originalPair) + introduction, "人".repeat(5_461) + "。")
        for (invalid in edited) {
            assertThrows(VerseTestFormatFailure::class.java) { graph.operation { true }.decode(invalid) }
        }
        var checks = 10
        assertThrows(VerseTestFormatFailure::class.java) {
            graph.operation { checks-- > 0 }.encode(packet)
        }
        assertArrayEquals(packet, graph.operation { true }.decode(text))
    }

    @Test fun rejectsAnAuthenticatedEnvelopeAlteredThroughACanonicalCarrier() = key(1).use { key ->
        val style = StegoWordingProfile.PROSE_WINDOW
        val stages = listOf(MessageStage(MessageCipher.AES), MessageStage(MessageCipher.XCHACHA20))
        val body = "公开测试。".toCharArray()
        val text = StegoWordingCodec.seal(assets, style, body, key, stages, MessageKdf.NONE, false) { true }.text
        key(2).use { wrong ->
            assertThrows(MessageFailure::class.java) { StegoWordingCodec.open(assets, style, text, wrong, { true }) { true } }
        }
        val graph = VerseProfileAssets(assets).window.surface
        val bytes = graph.operation { true }.decode(text)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = graph.operation { true }.encode(bytes)
        assertThrows(MessageFailure::class.java) { StegoWordingCodec.open(assets, style, tampered, key, { true }) { true } }
        assertArrayEquals(body, StegoWordingCodec.open(assets, style, text, key, { true }) { true })
    }

    @Test fun clipboardFixtureAuthenticatesWithItsPublicTestKey() {
        val body = "公开平台回归样本\r\n保留原文本。".toCharArray()
        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        MessageCredential(MessageCredentialMode.RANDOM_KEY, encodedKey.toCharArray()).use { credential ->
            val style = StegoWordingProfile.PROSE_WINDOW
            val fixture = java.io.File("src/androidTest/assets/stego-wording-window-public.txt")
            assertArrayEquals(body, StegoWordingCodec.open(assets, style, fixture.readText(), credential, { true }) { true })
        }
    }

    private data class Vector(val size: Int, val max: Boolean, val utf8Bytes: Int, val hash: String)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun key(value: Byte) = MessageCredential(
        MessageCredentialMode.RANDOM_KEY,
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { value }).toCharArray(),
    )
}
