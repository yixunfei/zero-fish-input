package dev.zeroinput.ime.textstego

import android.app.Application
import dev.zeroinput.ime.core.stego.VerseTestBody
import dev.zeroinput.ime.core.stego.VerseTestCapacityFailure
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.ime.core.stego.VerseTestSurface
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageCredentialMode
import dev.zeroinput.security.message.MessageEnvelope
import dev.zeroinput.security.message.MessageFailure
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageStage
import dev.zeroinput.security.message.VerseTestEnvelope
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VerseTestWorkbenchTest {
    private val assets get() = RuntimeEnvironment.getApplication().assets
    private fun asset(name: String) = assets.open("themed-verse/$name").use { it.readBytes() }

    @Test fun opensFrozenHostCarrierAndNewLocalCarrierByteForByte() {
        val workbench = VerseTestWorkbench(assets)
        val original = workbench.sample()
        val (host, restored) = workbench.open(workbench.hostSample())
        assertArrayEquals(original, restored)
        assertEquals(1856, host.originalBytes)
        assertEquals(418, host.envelopeBytes)
        assertEquals(3868, host.utf8Bytes)
        assertTrue(host.passesTarget)
        val made = workbench.seal(original)
        assertEquals(418, made.envelopeBytes)
        assertNotEquals(host.text, made.text)
        assertArrayEquals(original, workbench.open(made.text).second)
    }

    @Test fun rejectsTruncationIllegalLineAndChangedCiphertext() {
        val workbench = VerseTestWorkbench(assets)
        val carrier = workbench.hostSample()
        val routing = VerseTestSurface(asset("opening.bin"))
        val cipher = routing.decode(carrier)
        assertThrows(VerseTestFormatFailure::class.java) { workbench.open(carrier.dropLast(1)) }
        assertThrows(VerseTestFormatFailure::class.java) { workbench.open("人人人人人" + carrier.drop(5)) }
        assertThrows(VerseTestFormatFailure::class.java) { workbench.open(carrier.replaceFirst('\n', '\r')) }
        cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        assertThrows(MessageFailure::class.java) { workbench.open(routing.encode(cipher)) }
    }

    @Test fun rejectsWrongKeyCoverAndAuthenticatedUnsupportedStage() {
        val carrier = VerseTestWorkbench(assets).hostSample()
        val cipher = VerseTestSurface(asset("opening.bin")).decode(carrier)
        assertThrows(MessageFailure::class.java) { VerseTestEnvelope.open(cipher, ByteArray(32)) }
        val key = ByteArray(32) { it.toByte() }
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(key).toCharArray()
        MessageCredential(MessageCredentialMode.RANDOM_KEY, text).use { credential ->
            val plain = "public fixture".toCharArray()
            val differentCover = MessageEnvelope().seal("other public configuration".toCharArray(),
                plain, credential, kdf = MessageKdf.NONE)
            assertThrows(MessageFailure::class.java) { VerseTestEnvelope.open(differentCover, key) }
            val sameCover = "ZeroInput/host-verse/v1/a1790ee980dc97d76d1d5d90c37dfdfc0b50718bcc2a1f857dbb34e6cf66ff1a"
            val differentStage = MessageEnvelope().seal(sameCover.toCharArray(), plain, credential,
                listOf(MessageStage(MessageCipher.AES, 128)), MessageKdf.NONE)
            assertThrows(MessageFailure::class.java) { VerseTestEnvelope.open(differentStage, key) }
        }
    }

    @Test fun enforcesClosedGbPacketAndFrozenAssetBounds() {
        val mapping = asset("gb-map.bin")
        val dictionary = asset("gb18030.zdict")
        val body = VerseTestBody(mapping, dictionary)
        for (original in listOf(byteArrayOf(0), ByteArray(2048) { 'A'.code.toByte() },
            "\uE7C7、𠀋、🙂\r\ne\u0301".toByteArray())) {
            assertArrayEquals(original, body.decode(body.encode(original)))
        }
        assertThrows(VerseTestFormatFailure::class.java) { body.encode(byteArrayOf(0xc0.toByte(), 0x80.toByte())) }
        assertThrows(VerseTestCapacityFailure::class.java) { body.encode(ByteArray(2049)) }
        assertThrows(VerseTestFormatFailure::class.java) { body.decode(compressed(byteArrayOf(0x81.toByte()))) }
        assertThrows(VerseTestFormatFailure::class.java) { body.decode(compressed(ByteArray(100) { 65 })) }
        assertThrows(VerseTestFormatFailure::class.java) { body.decode(body.encode(byteArrayOf(65)) + byteArrayOf(0)) }
        assertThrows(VerseTestFormatFailure::class.java) { body.decode(byteArrayOf(5, 0, 1, 0)) }
        assertThrows(VerseTestFormatFailure::class.java) { VerseTestBody(mapping, dictionary.copyOf(10)) }
        val changedPool = asset("opening.bin").apply { this[0] = 0 }
        assertThrows(VerseTestFormatFailure::class.java) { VerseTestSurface(changedPool) }
    }

    @Test fun countsExactLinesAndRecoversLeadingZeroAtMultipleEnvelopeBuckets() {
        val route = VerseTestSurface(asset("opening.bin"))
        for (size in listOf(162, 226, 418, 930, 1506)) {
            val cipher = ByteArray(size) { if (it < 5) 0 else (it * 67).toByte() }
            val made = route.encode(cipher)
            assertTrue(made.toByteArray().size <= 16_384 && made.length <= 8_192)
            assertArrayEquals(cipher, route.decode(made))
        }
        assertThrows(VerseTestCapacityFailure::class.java) { route.encode(ByteArray(2210)) }
    }

    private fun compressed(gb: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(gb) }
        return byteArrayOf(4, 0, 1) + out.toByteArray()
    }
}
