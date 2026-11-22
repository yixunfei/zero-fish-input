package dev.zeroinput.ime.textstego

import android.app.Application
import dev.zeroinput.ime.core.stego.VerseTestCapacityFailure
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.ime.core.stego.WordContextBody
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageStage
import dev.zeroinput.security.message.WrappedWordingEnvelope
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WordContextBodyTest {
    private val assets get() = RuntimeEnvironment.getApplication().assets
    private val stages = listOf(MessageStage(MessageCipher.AES), MessageStage(MessageCipher.XCHACHA20))

    @Test fun allThreeSelectedModesMatchIndependentHostPacketsAndRecoverExactText() {
        val body = VerseProfileAssets(assets).wordBody
        val vectors = listOf(
            Vector(5, "公开回读𠀋🙂e\u0301\r\n", "90d8de56b96e81f57f2d79dc00fc20502201be8df27080c08e728415d7769fa0"),
            Vector(6, "上午開會，下午回信。上午開會，下午散步。週末讀書，週末散步。".repeat(4),
                "5d1930bc144b69c4fec4e140595dc5771ed432411001034158bba67030717a92"),
            Vector(7, "本計畫之研究結果可作為後續推動使用之參考。以上分析結果顯示不同群體所面臨的條件具有差異。" +
                "建議建立服務流程，並加強資訊公開及人員培訓。本研究透過問卷調查及深度訪談釐清實際需求。" +
                "研究方法是蒐集現行制度及國內外文獻資料，並分析其差異。將所蒐集的調查資訊加以分類，並就關鍵問題提出建議。" +
                "根據調查結果提出政策建議，以利相關單位參考。近年來，相關政策逐步調整，提供各機關運用。",
                "76ffc4c1f6c8b8a8dd75b790e8b0a48c2be02ec7df6ac5228ae3251b1e1313a9"),
        )
        for (vector in vectors) {
            val original = vector.text.toByteArray()
            val packet = body.encode(original, ::envelopeSize) { true }
            assertEquals(vector.mode, packet[0].toInt())
            assertEquals(vector.digest, sha256(packet))
            assertArrayEquals(original, body.decode(packet) { true })
            assertThrows(VerseTestFormatFailure::class.java) { body.decode(packet + byteArrayOf(0)) { true } }
            assertThrows(VerseTestFormatFailure::class.java) { body.decode(packet.copyOf(packet.size - 1)) { true } }
            assertThrows(VerseTestFormatFailure::class.java) { body.decode(packet) { false } }
        }
        val firstAgain = body.encode(vectors[0].text.toByteArray(), ::envelopeSize) { true }
        assertEquals(vectors[0].digest, sha256(firstAgain))
    }

    @Test fun rejectsInvalidStateDeclaredLengthsUnknownModeAndCorruptedModel() {
        val body = VerseProfileAssets(assets).wordBody
        val broken = listOf(
            byteArrayOf(4, 0, 1, 0),
            byteArrayOf(8, 0, 1, 0),
            byteArrayOf(5, 0, 0, 0),
            byteArrayOf(5, 8, 1, 0),
            byteArrayOf(6, 0, 1, 0, 0, 0, 0),
            byteArrayOf(7, 0, 1, 0, 0, 0, 0x80.toByte()),
            ByteArray(2049) { 5 },
        )
        for (packet in broken)
            assertThrows(VerseTestFormatFailure::class.java) { body.decode(packet) { true } }
        val changed = readVerseAsset(assets, "word-context-v1.bin", WordContextBody.ASSET_BYTES)
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        assertThrows(VerseTestFormatFailure::class.java) { WordContextBody(changed) }
        assertThrows(VerseTestCapacityFailure::class.java) { body.encode(ByteArray(2049), ::envelopeSize) { true } }
        assertThrows(VerseTestFormatFailure::class.java) {
            body.encode(byteArrayOf(0xc0.toByte(), 0x80.toByte()), ::envelopeSize) { true }
        }
        var checks = 0
        assertThrows(VerseTestFormatFailure::class.java) {
            body.encode("截止时间測試".repeat(15).toByteArray(), ::envelopeSize) { ++checks < 3 }
        }
    }

    private fun envelopeSize(size: Int): Int = WrappedWordingEnvelope.size(size, stages)

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private data class Vector(val mode: Int, val text: String, val digest: String)
}
