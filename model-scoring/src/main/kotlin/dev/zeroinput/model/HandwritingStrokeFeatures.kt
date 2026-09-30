package dev.zeroinput.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Bounded Zinnia stroke features; only the current request owns this buffer.
 * Adapted from Zinnia, Copyright (c) 2005-2007 Taku Kudo, BSD-3-Clause.
 * See LICENSES/Zinnia-BSD-3-Clause.txt and docs/handwriting-quality-validation.md.
 */
internal class HandwritingStrokeFeatures private constructor(private val values: FloatArray) : AutoCloseable {
    operator fun get(index: Int): Float = values[slot(index)]

    override fun close() { values.fill(0f) }

    private fun add(index: Int, value: Float) { values[slot(index)] = value }

    private fun basic(offset: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        add(offset + 1, 10f * sqrt(dx * dx + dy * dy))
        add(offset + 2, atan2(dy, dx))
        add(offset + 3, 10f * (x1 - .5f))
        add(offset + 4, 10f * (y1 - .5f))
        add(offset + 5, 10f * (x2 - .5f))
        add(offset + 6, 10f * (y2 - .5f))
        add(offset + 7, atan2(y1 - .5f, x1 - .5f))
        add(offset + 8, atan2(y2 - .5f, x2 - .5f))
        add(offset + 9, 10f * sqrt((x1 - .5f) * (x1 - .5f) + (y1 - .5f) * (y1 - .5f)))
        add(offset + 10, 10f * sqrt((x2 - .5f) * (x2 - .5f) + (y2 - .5f) * (y2 - .5f)))
        add(offset + 11, 5f * dx)
        add(offset + 12, 5f * dy)
    }

    private fun vertices(points: FloatArray, first: Int, last: Int, stroke: Int, node: Int) {
        if (node > 50) return
        val x1 = points[first]
        val y1 = points[first + 1]
        val x2 = points[last]
        val y2 = points[last + 1]
        basic(stroke * 1000 + node * 20, x1, y1, x2, y2)
        val dx = x2 - x1
        val dy = y2 - y1
        val divisor = dx * dx + dy * dy
        if (first == last || divisor == 0f) return
        val offset = y2 * x1 - x2 * y1
        var greatest = -1f
        var best = first
        var index = first
        while (index < last) {
            val distance = abs(dx * points[index + 1] - dy * points[index] + offset)
            if (distance > greatest) { greatest = distance; best = index }
            index += 2
        }
        if (greatest * greatest / divisor > .001f && best > first && best < last) {
            vertices(points, first, best, stroke, node * 2 + 1)
            vertices(points, best, last, stroke, node * 2 + 2)
        }
    }

    companion object {
        private const val GLOBAL_FEATURE = 2_000_000
        private const val COMPACT_GLOBAL = 150_000
        private const val SIZE = COMPACT_GLOBAL + 49

        fun validIndex(index: Int): Boolean = index in 0 until COMPACT_GLOBAL ||
            index in GLOBAL_FEATURE..GLOBAL_FEATURE + 48

        private fun slot(index: Int): Int = if (index >= GLOBAL_FEATURE) index - GLOBAL_FEATURE + COMPACT_GLOBAL else index

        fun from(strokes: List<FloatArray>): HandwritingStrokeFeatures? {
            val transform = HandwritingGeometry.transform(strokes) ?: return null
            val result = HandwritingStrokeFeatures(FloatArray(SIZE))
            result.add(0, 1f)
            var previousX = 0f
            var previousY = 0f
            strokes.forEachIndexed { strokeIndex, original ->
                val points = original.clone()
                try {
                    var index = 0
                    while (index < points.size) {
                        points[index] = (points[index] * transform.scale + transform.offsetX) / 40f - .1f
                        points[index + 1] = (points[index + 1] * transform.scale + transform.offsetY) / 40f - .1f
                        index += 2
                    }
                    result.vertices(points, 0, points.size - 2, strokeIndex, 0)
                    if (strokeIndex > 0) result.basic(100_000 + strokeIndex * 1000, previousX, previousY, points[0], points[1])
                    previousX = points[points.size - 2]
                    previousY = points.last()
                } finally { points.fill(0f) }
            }
            result.add(GLOBAL_FEATURE, strokes.size.toFloat())
            result.add(GLOBAL_FEATURE + strokes.size, 10f)
            return result
        }
    }
}
