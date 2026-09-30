package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.GlideKey
import dev.zeroinput.engine.api.GlideRequest
import kotlin.math.abs
import kotlin.math.hypot

/** Distances are in actual key widths/heights, independent of panel position or size. */
internal class GlideGeometry(request: GlideRequest) {
    private val scaleX = request.keys.map { it.right - it.left }.sorted().let { it[it.size / 2] }
    private val scaleY = request.keys.map { it.bottom - it.top }.sorted().let { it[it.size / 2] }
    private val keys = request.keys.associateBy { it.code }
    val trace: GlidePath = GlidePath.create(FloatArray(request.points.size * 2) { index ->
        val point = request.points[index / 2]
        if (index % 2 == 0) point.x / scaleX else point.y / scaleY
    })

    fun endpointKeys(start: Boolean): List<Char> {
        val index = if (start) 0 else trace.samples.size - 2
        return keys.values.map { key ->
            key.code to hypot(trace.samples[index] - centerX(key), trace.samples[index + 1] - centerY(key))
        }.filter { it.second <= MAX_ENDPOINT_DISTANCE }
            .sortedBy { it.second }.take(ENDPOINT_NEIGHBORS).map { it.first }
    }

    fun template(code: String): GlidePath? {
        val vertices = FloatArray(code.length * 2)
        for (index in code.indices) {
            val key = keys[code[index]] ?: return null
            vertices[index * 2] = centerX(key)
            vertices[index * 2 + 1] = centerY(key)
        }
        return GlidePath.create(vertices)
    }

    private fun centerX(key: GlideKey) = (key.left + key.right) * 0.5f / scaleX
    private fun centerY(key: GlideKey) = (key.top + key.bottom) * 0.5f / scaleY

    companion object {
        private const val ENDPOINT_NEIGHBORS = 3
        private const val MAX_ENDPOINT_DISTANCE = 1.65f
    }
}

internal class GlidePath private constructor(val samples: FloatArray, val length: Float) {
    fun alignedDistance(other: GlidePath): Float {
        var total = 0f
        for (index in samples.indices step 2) total += squaredDistance(samples, index, other.samples, index)
        return total / SAMPLE_COUNT
    }

    fun endpointDistance(other: GlidePath): Float =
        (squaredDistance(samples, 0, other.samples, 0) +
            squaredDistance(samples, samples.size - 2, other.samples, samples.size - 2)) / 2

    fun warpedDistance(other: GlidePath): Float {
        var previous = FloatArray(SAMPLE_COUNT + 1) { Float.POSITIVE_INFINITY }
        var current = FloatArray(SAMPLE_COUNT + 1)
        previous[0] = 0f
        for (row in 1..SAMPLE_COUNT) {
            current.fill(Float.POSITIVE_INFINITY)
            for (column in maxOf(1, row - WARP_WINDOW)..minOf(SAMPLE_COUNT, row + WARP_WINDOW)) {
                val distance = squaredDistance(samples, (row - 1) * 2, other.samples, (column - 1) * 2)
                current[column] = distance + minOf(previous[column], current[column - 1], previous[column - 1])
            }
            val old = previous
            previous = current
            current = old
        }
        return previous[SAMPLE_COUNT] / SAMPLE_COUNT
    }

    companion object {
        private const val SAMPLE_COUNT = 32
        private const val WARP_WINDOW = 8

        fun create(vertices: FloatArray): GlidePath {
            val cumulative = FloatArray(vertices.size / 2)
            for (index in 1 until cumulative.size) {
                cumulative[index] = cumulative[index - 1] + hypot(
                    vertices[index * 2] - vertices[index * 2 - 2],
                    vertices[index * 2 + 1] - vertices[index * 2 - 1],
                )
            }
            val length = cumulative.last()
            val samples = FloatArray(SAMPLE_COUNT * 2)
            var segment = 1
            for (sample in 0 until SAMPLE_COUNT) {
                val distance = length * sample / (SAMPLE_COUNT - 1)
                while (segment < cumulative.lastIndex && cumulative[segment] < distance) segment++
                val end = segment.coerceAtMost(cumulative.lastIndex)
                val start = (end - 1).coerceAtLeast(0)
                val span = cumulative[end] - cumulative[start]
                val fraction = if (abs(span) < 0.00001f) 0f else (distance - cumulative[start]) / span
                for (axis in 0..1) samples[sample * 2 + axis] = vertices[start * 2 + axis] +
                    (vertices[end * 2 + axis] - vertices[start * 2 + axis]) * fraction
            }
            return GlidePath(samples, length)
        }

        private fun squaredDistance(first: FloatArray, a: Int, second: FloatArray, b: Int): Float {
            val x = first[a] - second[b]
            val y = first[a + 1] - second[b + 1]
            return x * x + y * y
        }
    }
}
