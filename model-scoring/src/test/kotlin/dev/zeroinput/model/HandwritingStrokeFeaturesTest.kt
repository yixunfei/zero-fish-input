package dev.zeroinput.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingStrokeFeaturesTest {
    @Test fun publicHorizontalStrokeMatchesZinniaFeatureEquations() {
        requireNotNull(HandwritingStrokeFeatures.from(listOf(floatArrayOf(.1f, .5f, .9f, .5f)))).use { features ->
            assertEquals(1f, features[0], 0f)
            assertEquals(9f, features[1], .0001f)
            assertEquals(0f, features[2], .0001f)
            assertEquals(-4.5f, features[3], .0001f)
            assertEquals(4.5f, features[5], .0001f)
            assertEquals(4.5f, features[11], .0001f)
            assertEquals(1f, features[2_000_000], 0f)
            assertEquals(10f, features[2_000_001], 0f)
        }
    }

    @Test fun translatedAndRescaledInputProducesEquivalentFeatures() {
        requireNotNull(HandwritingStrokeFeatures.from(listOf(floatArrayOf(.1f, .2f, .5f, .4f)))).use { first ->
            requireNotNull(HandwritingStrokeFeatures.from(listOf(floatArrayOf(.3f, .4f, .9f, .7f)))).use { second ->
                for (index in 0..12) assertEquals(first[index], second[index], .0001f)
            }
        }
    }

    @Test fun singlePointIsFiniteAndClosedBuffersAreCleared() {
        val features = requireNotNull(HandwritingStrokeFeatures.from(listOf(floatArrayOf(.2f, .3f))))
        assertTrue((0..12).all { features[it].isFinite() })
        features.close()
        assertTrue((0..12).all { features[it] == 0f })
        assertEquals(0f, features[2_000_001], 0f)
    }

    @Test fun malformedPointsAndExcessiveStrokesAreRejected() {
        assertNull(HandwritingStrokeFeatures.from(listOf(floatArrayOf(Float.NaN, .1f))))
        assertNull(HandwritingStrokeFeatures.from(List(49) { floatArrayOf(.2f, .3f) }))
        assertNull(HandwritingStrokeFeatures.from(listOf(FloatArray(1026))))
        assertTrue(!HandwritingStrokeFeatures.validIndex(150_000))
        assertTrue(!HandwritingStrokeFeatures.validIndex(2_000_049))
        assertTrue(HandwritingStrokeFeatures.validIndex(2_000_048))
    }
}
