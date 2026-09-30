package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import java.io.Closeable
import java.lang.ref.WeakReference

/** A recycled row cannot turn a gesture begun on another expression into a selection. */
internal class ExpressionCellView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    var bindingRevision = -1L
    private var gestureRevision = -1L
    private var gesturePending = false
    private var artworkKey: String? = null
    private var artwork: Bitmap? = null
    private var artworkRequest: Closeable? = null
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artworkBounds = RectF()

    fun bindExpression(entry: EmojiEntry?) {
        artworkRequest?.close()
        artworkRequest = null
        artworkKey = entry?.artworkKey
        artwork = null
        text = if (artworkKey == null) entry?.value.orEmpty() else context.getString(R.string.expression_image_loading)
        if (isAttachedToWindow) requestArtwork()
        invalidate()
    }

    private fun requestArtwork() {
        val key = artworkKey ?: return
        if (artwork != null) return
        val reference = WeakReference(this)
        artworkRequest = EmojiArtwork.load(context, key) { bitmap ->
            val view = reference.get()
            if (view != null && view.artworkKey == key && view.isAttachedToWindow) {
                view.artwork = bitmap
                view.text = if (bitmap == null) view.context.getString(R.string.expression_image_unavailable) else ""
                view.invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = artwork ?: return
        val size = minOf(32 * resources.displayMetrics.density, (width - paddingLeft - paddingRight).toFloat(),
            (height - paddingTop - paddingBottom).toFloat()).coerceAtLeast(1f)
        val left = (width - size) / 2
        val top = (height - size) / 2
        artworkBounds.set(left, top, left + size, top + size)
        canvas.drawBitmap(bitmap, null, artworkBounds, artworkPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestArtwork()
    }

    override fun onDetachedFromWindow() {
        artworkRequest?.close()
        artworkRequest = null
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureRevision = bindingRevision
            gesturePending = true
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesturePending = false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun performClick(): Boolean {
        val current = !gesturePending || gestureRevision == bindingRevision
        gesturePending = false
        return current && super.performClick()
    }

    override fun performLongClick(): Boolean =
        (!gesturePending || gestureRevision == bindingRevision) && super.performLongClick()
}
