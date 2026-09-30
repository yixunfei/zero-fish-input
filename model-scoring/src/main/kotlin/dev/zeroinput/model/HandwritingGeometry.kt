package dev.zeroinput.model

/** Validates untrusted strokes before Android bitmap allocation or native inference. */
internal object HandwritingGeometry {
    data class Transform(val scale: Float, val offsetX: Float, val offsetY: Float)

    fun transform(strokes: List<FloatArray>): Transform? {
        if (strokes.isEmpty() || strokes.size > 48 ||
            strokes.any { it.size < 2 || it.size > 1024 || it.size % 2 != 0 }) return null
        var left = 1f
        var top = 1f
        var right = 0f
        var bottom = 0f
        strokes.forEach { stroke ->
            var index = 0
            while (index < stroke.size) {
                val x = stroke[index++]
                val y = stroke[index++]
                if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        val scale = 36f / maxOf(right - left, bottom - top, 0.03f)
        return Transform(scale, 24f - (left + right) * scale / 2f, 24f - (top + bottom) * scale / 2f)
    }
}
