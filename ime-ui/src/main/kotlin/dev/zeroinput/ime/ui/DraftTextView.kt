package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

/** Focus-free caret intent; never creates an InputConnection or changes draft text itself. */
internal class DraftTextView(context: Context) : AppCompatTextView(context) {
    var onCaretRequested: (KeyboardAction.MoveDraftCaret) -> Unit = {}
    private var caret: DraftCaretUi? = null
    private var compositionLength = 0
    private var pressed: DraftCaretUi? = null
    private var renderVersion = 0L
    private var pressedVersion = 0L
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val caretPaint = Paint()

    init {
        isSaveEnabled = false
        filterTouchesWhenObscured = true
    }

    fun renderCaret(value: DraftCaretUi?, preeditLength: Int = 0) {
        renderVersion++
        caret = value
        compositionLength = preeditLength
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val offset = caret?.offset?.takeIf { it >= 0 }?.plus(compositionLength) ?: return
        val current = layout ?: return
        if (offset > text.length) return
        val line = current.getLineForOffset(offset)
        // The parent already translates the canvas by this view's scroll offset.
        val x = totalPaddingLeft + current.getPrimaryHorizontal(offset)
        val top = totalPaddingTop + current.getLineTop(line)
        val bottom = totalPaddingTop + current.getLineBottom(line)
        caretPaint.color = currentTextColor
        caretPaint.strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
        canvas.drawLine(x, top.toFloat(), x, bottom.toFloat(), caretPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = caret
                pressedVersion = renderVersion
                downX = event.x; downY = event.y; downAt = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) pressed = null
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        val request = if (event.actionMasked == MotionEvent.ACTION_UP) pressed.also { pressed = null } else null
        val handled = super.onTouchEvent(event)
        if (request != null && pressedVersion == renderVersion && request == caret &&
            abs(event.x - downX) <= slop && abs(event.y - downY) <= slop &&
            event.eventTime - downAt < ViewConfiguration.getLongPressTimeout() &&
            isEnabled && layout != null) {
            val displayOffset = getOffsetForPosition(event.x, event.y)
            val offset = when {
                compositionLength == 0 || displayOffset <= request.offset -> displayOffset
                displayOffset < request.offset + compositionLength -> request.offset
                else -> displayOffset - compositionLength
            }
            onCaretRequested(KeyboardAction.MoveDraftCaret(offset, request.revision))
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        pressed = null
        caret = null
        super.onDetachedFromWindow()
    }
}
