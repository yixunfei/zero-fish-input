package dev.zeroinput.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlideRequestTest {
    private val key = GlideKey('a', 0f, 0f, 0.1f, 0.25f)
    private val points = listOf(GlidePoint(0.1f, 0.2f, 0), GlidePoint(0.4f, 0.5f, 100))

    @Test fun `request owns immutable copies across worker handoff`() {
        val inputPoints = points.toMutableList()
        val inputKeys = mutableListOf(key)
        val request = GlideRequest(GlideLayout.ENGLISH_QWERTY, inputPoints, inputKeys)
        inputPoints.clear()
        inputKeys.clear()
        assertEquals(2, request.points.size)
        assertEquals(1, request.keys.size)
        assertThrows(UnsupportedOperationException::class.java) { (request.points as MutableList).clear() }
    }

    @Test fun `invalid coordinates time ordering and unbounded request fields are rejected`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { GlidePoint(value, 0f, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { GlidePoint(0f, 0f, 30_001) }
        assertThrows(IllegalArgumentException::class.java) { GlideKey('a', 0f, 0f, 0f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { GlideKey(' ', 0f, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) {
            GlideRequest(GlideLayout.ENGLISH_QWERTY, points.reversed(), listOf(key))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlideRequest(GlideLayout.ENGLISH_QWERTY, List(257) { points[0] }, listOf(key))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlideRequest(GlideLayout.ENGLISH_QWERTY, points, listOf(key, key))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlideRequest(GlideLayout.ENGLISH_QWERTY, points, listOf(key), maxCandidates = 17)
        }
    }

    @Test fun `public rows enforce active layout alphabet and resource bounds`() {
        GlideLexiconEntry(GlideLayout.ENGLISH_QWERTY, "don't")
        GlideLexiconEntry(GlideLayout.DOUBLE_PINYIN_MICROSOFT, "m;")
        for ((layout, code) in listOf(
            GlideLayout.PINYIN_NINE_KEY to "01", GlideLayout.PINYIN_QWERTY to "ni hao",
            GlideLayout.DOUBLE_PINYIN_ZIRANMA to "m;", GlideLayout.ENGLISH_QWERTY to "'",
        )) assertThrows(IllegalArgumentException::class.java) { GlideLexiconEntry(layout, code) }
        assertThrows(IllegalArgumentException::class.java) {
            GlideLexiconEntry(GlideLayout.ENGLISH_QWERTY, "a".repeat(49))
        }
    }
}
