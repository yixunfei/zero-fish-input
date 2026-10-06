package dev.zeroinput.ime.ui

import android.graphics.Bitmap
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

/** Owns only a prepared display bitmap; never decodes or opens files on the input thread. */
internal class KeyboardBackgroundDrawable(
    private val appearance: KeyboardAppearance,
    private val surface: Int,
    private val accent: Int,
    private val image: Bitmap?,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private val layerBounds = RectF()
    private var gradient: Shader? = null

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        layerBounds.set(bounds)
        val base = appearance.backgroundColor ?: surface
        gradient = LinearGradient(0f, 0f, bounds.width().toFloat().coerceAtLeast(1f),
            bounds.height().toFloat().coerceAtLeast(1f), base, accent, Shader.TileMode.CLAMP)
        image?.let {
            val scale = maxOf(bounds.width().toFloat() / it.width, bounds.height().toFloat() / it.height)
            val width = it.width * scale
            val height = it.height * scale
            target.set(bounds.exactCenterX() - width / 2, bounds.exactCenterY() - height / 2,
                bounds.exactCenterX() + width / 2, bounds.exactCenterY() + height / 2)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(surface)
        val layer = canvas.saveLayerAlpha(layerBounds, appearance.backgroundOpacity * 255 / 100)
        paint.color = appearance.backgroundColor ?: surface
        paint.shader = if (appearance.background == KeyboardBackground.GRADIENT ||
            appearance.background == KeyboardBackground.TEXTURE) gradient else null
        canvas.drawRect(bounds, paint)
        paint.shader = null
        if (appearance.background == KeyboardBackground.IMAGE && image != null) {
            canvas.drawBitmap(image, null, target, paint)
        }
        if (appearance.background == KeyboardBackground.TEXTURE) {
            paint.color = ColorUtils.setAlphaComponent(accent, 55)
            paint.strokeWidth = density / 2
            var x = bounds.left.toFloat() - bounds.height()
            while (x < bounds.right) {
                canvas.drawLine(x, bounds.bottom.toFloat(), x + bounds.height(), bounds.top.toFloat(), paint)
                x += density * 8
            }
        }
        if (appearance.background != KeyboardBackground.SOLID) {
            paint.color = ColorUtils.setAlphaComponent(surface, appearance.backgroundDim * 255 / 100)
            canvas.drawRect(bounds, paint)
        }
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.OPAQUE
}
