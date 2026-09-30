package dev.zeroinput.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandwritingGeometryTest {
    @Test fun scaledAndTranslatedStrokesRetainTheSameRasterShape() {
        val original = floatArrayOf(.1f, .2f, .5f, .4f)
        val translated = floatArrayOf(.3f, .4f, .9f, .7f)
        val first = requireNotNull(HandwritingGeometry.transform(listOf(original)))
        val second = requireNotNull(HandwritingGeometry.transform(listOf(translated)))
        original.indices.step(2).forEach { index ->
            assertEquals(original[index] * first.scale + first.offsetX,
                translated[index] * second.scale + second.offsetX, .0001f)
            assertEquals(original[index + 1] * first.scale + first.offsetY,
                translated[index + 1] * second.scale + second.offsetY, .0001f)
        }
    }

    @Test fun aPointDoesNotDivideByZero() {
        val transform = requireNotNull(HandwritingGeometry.transform(listOf(floatArrayOf(.2f, .3f))))
        assertEquals(24f, .2f * transform.scale + transform.offsetX, .001f)
        assertEquals(24f, .3f * transform.scale + transform.offsetY, .001f)
    }

    @Test fun malformedStrokesFailBeforeRasterAllocation() {
        assertNull(HandwritingGeometry.transform(emptyList()))
        assertNull(HandwritingGeometry.transform(List(49) { floatArrayOf(.2f, .3f) }))
        assertNull(HandwritingGeometry.transform(listOf(floatArrayOf(.2f))))
        assertNull(HandwritingGeometry.transform(listOf(FloatArray(1026))))
        assertNull(HandwritingGeometry.transform(listOf(floatArrayOf(-.1f, .3f))))
        assertNull(HandwritingGeometry.transform(listOf(floatArrayOf(.2f, 1.1f))))
        assertNull(HandwritingGeometry.transform(listOf(floatArrayOf(Float.NaN, .3f))))
        assertNull(HandwritingGeometry.transform(listOf(floatArrayOf(.2f, Float.POSITIVE_INFINITY))))
        assertNotNull(HandwritingGeometry.transform(List(48) { FloatArray(1024) { .5f } }))
    }
}
