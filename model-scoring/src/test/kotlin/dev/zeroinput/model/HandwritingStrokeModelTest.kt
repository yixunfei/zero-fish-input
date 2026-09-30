package dev.zeroinput.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingStrokeModelTest {
    @Test fun packedSparseModelUsesFeaturesBiasAndDistinctHanLabels() {
        val bytes = fixture()
        val model = HandwritingStrokeModel.read(bytes.inputStream(), bytes.size.toLong())
        requireNotNull(HandwritingStrokeFeatures.from(listOf(floatArrayOf(.1f, .5f, .9f, .5f)))).use { features ->
            val result = model.recognize(features) { false }
            assertEquals(listOf("體", "中"), result.map { it.text })
            assertEquals(6f, result.first().score, .0001f)
            assertTrue(model.recognize(features) { true }.isEmpty())
        }
    }

    @Test fun corruptCountsIndexesLabelsAndNonFiniteWeightsFailClosed() {
        val changes = listOf<Pair<Int, Int>>(
            0 to 0, 4 to 2, 8 to Int.MAX_VALUE, 12 to 0, 16 to Int.MAX_VALUE,
            24 to 'A'.code, 32 to '中'.code, 28 to Float.NaN.toRawBits(),
            44 to 0, 48 to 1, 56 to 3, 60 to 0x00010002, 68 to Float.POSITIVE_INFINITY.toRawBits(),
        )
        changes.forEach { (offset, value) ->
            val bytes = fixture()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertTrue("Invalid model boundary at $offset must reject", runCatching {
                HandwritingStrokeModel.read(bytes.inputStream(), bytes.size.toLong())
            }.isFailure)
        }
    }

    @Test fun truncationTrailingBytesAndIncorrectLengthFailClosed() {
        val bytes = fixture()
        listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(0)).forEach { malformed ->
            assertTrue(runCatching { HandwritingStrokeModel.read(malformed.inputStream(), bytes.size.toLong()) }.isFailure)
        }
        assertTrue(runCatching { HandwritingStrokeModel.read(bytes.inputStream(), bytes.size.toLong() + 1) }.isFailure)
    }

    private fun fixture(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeBytes("ZSH1")
            listOf(1, 2, 2, 4, 2_000_001).forEach { output.little(it) }
            output.little('中'.code); output.little((-.1f).toRawBits())
            output.little('體'.code); output.little((-.2f).toRawBits())
            listOf(0, 2_000_001, 0, 2, 4).forEach { output.little(it) }
            listOf(0, 1, 0, 1).forEach { output.writeShort(java.lang.Short.reverseBytes(it.toShort()).toInt()) }
            listOf(.1f, .2f, .3f, .6f).forEach { output.little(it.toRawBits()) }
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.little(value: Int) { writeInt(Integer.reverseBytes(value)) }
}
