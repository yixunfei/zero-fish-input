package dev.zeroinput.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Converts bounded normalized touch strokes to the OCR model's padded RGB input. */
internal object HandwritingRasterizer {
    const val HEIGHT = 48
    const val WIDTH = 96

    fun rasterize(strokes: List<FloatArray>): FloatArray? {
        val transform = HandwritingGeometry.transform(strokes) ?: return null
        val bitmap = Bitmap.createBitmap(HEIGHT, HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(HEIGHT * HEIGHT)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val (scale, offsetX, offsetY) = transform
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 2.5f
            }
            strokes.forEach { stroke ->
                val path = Path()
                path.moveTo(stroke[0] * scale + offsetX, stroke[1] * scale + offsetY)
                if (stroke.size == 2) path.lineTo(stroke[0] * scale + offsetX + 0.1f, stroke[1] * scale + offsetY)
                var index = 2
                while (index < stroke.size) {
                    path.lineTo(stroke[index] * scale + offsetX, stroke[index + 1] * scale + offsetY)
                    index += 2
                }
                canvas.drawPath(path, paint)
                path.reset()
            }
            bitmap.getPixels(pixels, 0, HEIGHT, 0, 0, HEIGHT, HEIGHT)
            val plane = WIDTH * HEIGHT
            // PP-OCR expects normalized zero right padding, not white extra image content.
            val values = FloatArray(3 * plane)
            for (i in pixels.indices) {
                val grey = ((pixels[i] ushr 16) and 0xff) / 127.5f - 1f
                val target = i / HEIGHT * WIDTH + i % HEIGHT
                values[target] = grey
                values[plane + target] = grey
                values[2 * plane + target] = grey
            }
            return values
        } finally {
            pixels.fill(0)
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.recycle()
        }
    }
}
