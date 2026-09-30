package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/** Session-only touch strokes. Coordinates are normalized before leaving the UI. */
internal class HandwritingCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onChanged: (List<FloatArray>) -> Unit = {}
    private val strokes = ArrayList<FloatArray>()
    private val current = FloatArray(MAX_POINTS * 2)
    private var currentSize = 0
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.displayMetrics.density * 4f
    }
    private val path = Path()

    init {
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE))
        contentDescription = context.getString(R.string.handwriting_canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { drawStroke(canvas, it, it.size) }
        if (currentSize > 0) drawStroke(canvas, current, currentSize)
    }

    private fun drawStroke(canvas: Canvas, points: FloatArray, size: Int) {
        val extent = maxOf(width, height)
        path.reset()
        path.moveTo(points[0] * extent, points[1] * extent)
        if (size == 2) path.lineTo(points[0] * extent + 0.1f, points[1] * extent)
        var index = 2
        while (index < size) {
            path.lineTo(points[index] * extent, points[index + 1] * extent)
            index += 2
        }
        canvas.drawPath(path, ink)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (strokes.size >= MAX_STROKES) return false
                clearCurrent()
                activePointer = event.getPointerId(0)
                addPoint(event.x, event.y)
                // Starting a new stroke revokes previous candidates before this stroke ends.
                onChanged(emptyList())
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointer = event.findPointerIndex(activePointer)
                if (pointer < 0 || currentSize == 0) return true
                for (index in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(pointer, index), event.getHistoricalY(pointer, index))
                }
                addPoint(event.getX(pointer), event.getY(pointer))
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) != activePointer || currentSize == 0) return true
                addPoint(event.x, event.y)
                strokes.add(current.copyOf(currentSize))
                clearCurrent()
                notifyStrokes()
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                clearCurrent()
                notifyStrokes()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> return true
            else -> return false
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        // Both axes use the same physical scale on wide, floating and one-handed surfaces.
        val extent = maxOf(width, height).toFloat()
        val normalizedX = x.coerceIn(0f, width.toFloat()) / extent
        val normalizedY = y.coerceIn(0f, height.toFloat()) / extent
        if (currentSize >= 2 && kotlin.math.abs(current[currentSize - 2] - normalizedX) < 0.002f &&
            kotlin.math.abs(current[currentSize - 1] - normalizedY) < 0.002f) return
        if (currentSize == current.size) compactCurrent()
        current[currentSize++] = normalizedX
        current[currentSize++] = normalizedY
    }

    fun snapshot(): List<FloatArray> = strokes.map(FloatArray::clone)
    fun hasStrokes(): Boolean = strokes.isNotEmpty() || currentSize > 0

    private fun compactCurrent() {
        // Preserve the whole stroke and its final point rather than truncating long gestures.
        var target = 2
        var source = 4
        while (source < currentSize) {
            current[target++] = current[source++]
            current[target++] = current[source++]
            source += 2
        }
        current.fill(0f, target, currentSize)
        currentSize = target
    }

    private fun notifyStrokes() {
        val copied = snapshot()
        try { onChanged(copied) } finally { copied.forEach { it.fill(0f) } }
    }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        clearCurrent()
        strokes.removeAt(strokes.lastIndex).fill(0f)
        notifyStrokes()
        invalidate()
        return true
    }

    fun clear() {
        strokes.forEach { it.fill(0f) }
        strokes.clear()
        clearCurrent()
        onChanged(emptyList())
        invalidate()
    }

    private fun clearCurrent() {
        current.fill(0f)
        currentSize = 0
        activePointer = MotionEvent.INVALID_POINTER_ID
        path.reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) clear()
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_STROKES = 48
        const val MAX_POINTS = 512
    }
}
