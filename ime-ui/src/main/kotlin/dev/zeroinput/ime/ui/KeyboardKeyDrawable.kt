package dev.zeroinput.ime.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/** Decoration stays inside each key's unchanged rectangular touch target. */
internal class KeyboardKeyDrawable(
    private val base: Int,
    private val outline: Int,
    private val radius: Float,
    private val inset: Int,
    private val material: KeyboardMaterial,
    private val borders: Boolean,
    private val pressed: Boolean,
    private val density: Float,
    private val overBackground: Boolean,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        rect.inset(inset.toFloat() + density / 2, inset.toFloat() + density / 2)
        val colors = when (material) {
            KeyboardMaterial.CLASSIC, KeyboardMaterial.FLAT -> intArrayOf(base, base)
            KeyboardMaterial.RAISED -> intArrayOf(tint(Color.WHITE, .12f), base, tint(Color.BLACK, .12f))
            KeyboardMaterial.SOFT -> intArrayOf(tint(Color.WHITE, .16f), base, tint(Color.BLACK, .06f))
            KeyboardMaterial.METAL -> intArrayOf(tint(Color.WHITE, .28f), tint(Color.BLACK, .09f),
                tint(Color.WHITE, .18f), base, tint(Color.BLACK, .16f))
            KeyboardMaterial.FROSTED -> intArrayOf(tint(Color.WHITE, .18f), base)
        }
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom.coerceAtLeast(rect.top + 1),
            colors, null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        paint.colorFilter = filter
        val depth = if (material == KeyboardMaterial.RAISED && !pressed) 2 * density else 0f
        if (depth > 0) {
            val shader = paint.shader
            paint.shader = null
            paint.color = tint(Color.BLACK, .25f)
            paint.alpha = opacity
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = shader
        }
        rect.bottom -= depth
        val alpha = when {
            pressed -> 255
            material == KeyboardMaterial.FROSTED -> 180
            overBackground -> 225
            else -> 255
        }
        paint.alpha = alpha * opacity / 255
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (material == KeyboardMaterial.METAL) drawGrain(canvas)
        if (borders) {
            val shader = paint.shader
            paint.shader = null
            paint.color = outline
            paint.alpha = opacity * 150 / 255
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
            paint.shader = shader
        }
        rect.bottom += depth
    }

    private fun drawGrain(canvas: Canvas) {
        val shader = paint.shader
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = opacity * 12 / 255
        paint.strokeWidth = density / 2
        var y = rect.top + radius
        while (y < rect.bottom - radius) {
            canvas.drawLine(rect.left + density, y, rect.right - density, y, paint)
            y += 3 * density
        }
        paint.shader = shader
    }

    private fun tint(color: Int, ratio: Float) = ColorUtils.blendARGB(base, color, ratio)
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
