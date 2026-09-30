package dev.zeroinput.model

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingDecoderTest {
    private val characters = List(18_385) { index -> when (index) {
        1 -> "中"
        2 -> "文"
        3 -> "!"
        4 -> "體"
        5 -> "\uD840\uDC00"
        else -> ""
    } }

    @Test fun fullSequenceLikelihoodOutranksOneFrameSpike() {
        // Max-per-frame incorrectly prefers the 0.55 spike for 中 over sustained 文.
        val values = frames(floatArrayOf(.05f, .55f, .4f), floatArrayOf(.1f, .1f, .8f))
        assertEquals(listOf("文", "中"), decode(values))
    }

    @Test fun repeatedAdjacentSymbolsCollapseToOneCharacter() {
        val values = frames(floatArrayOf(.1f, .8f, .1f), floatArrayOf(.1f, .8f, .1f), floatArrayOf(.9f, .05f, .05f))
        assertEquals("中", decode(values).first())
    }

    @Test fun blankDominatedSequenceDoesNotInventCandidates() {
        assertTrue(decode(frames(floatArrayOf(.99f, .006f, .004f), floatArrayOf(.99f, .006f, .004f))).isEmpty())
    }

    @Test fun nonHanResultDoesNotTurnIntoUnrelatedHanAlternatives() {
        assertTrue(decode(frames(floatArrayOf(.01f, .01f, .01f, .97f))).isEmpty())
    }

    @Test fun traditionalAndSupplementaryHanRemainSelectable() {
        assertEquals(listOf("體", "\uD840\uDC00"), decode(frames(floatArrayOf(.01f, 0f, 0f, 0f, .69f, .3f))))
    }

    @Test fun malformedAndNonFiniteOutputFailsClosed() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.1f).forEach { invalid ->
            assertTrue(decode(frames(floatArrayOf(.1f, invalid, .2f))).isEmpty())
        }
        assertTrue(decode(frames(floatArrayOf(.1f, .1f, .1f))).isEmpty())
        val values = frames(floatArrayOf(.1f, .8f, .1f))
        assertTrue(HandwritingDecoder.decode(FloatBuffer.wrap(values), 257, characters).isEmpty())
        assertTrue(HandwritingDecoder.decode(FloatBuffer.wrap(values), 2, characters).isEmpty())
        assertTrue(HandwritingDecoder.decode(FloatBuffer.wrap(values), 1, characters, 0).isEmpty())
    }

    private fun decode(values: FloatArray) = HandwritingDecoder.decode(
        FloatBuffer.wrap(values), values.size / characters.size, characters,
    )

    private fun frames(vararg rows: FloatArray): FloatArray = FloatArray(rows.size * characters.size).also { values ->
        rows.forEachIndexed { step, row -> row.copyInto(values, step * characters.size) }
    }
}
