package dev.zeroinput.ime.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ViewConfiguration
import dev.zeroinput.engine.api.GlideKey
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlidePoint
import dev.zeroinput.engine.api.GlideRequest
import kotlin.math.hypot

enum class GlideLetterCase { LOWER, INITIAL_CAPITAL, UPPER }

/** Captures one bounded stroke; ordinary taps and independent pointers remain owned by keys. */
internal class GlideTouchTracker(
    private val host: KeyboardPanel,
    private val geometry: () -> List<GlideKey>,
) {
    var layout: GlideLayout? = null
        set(value) { if (field != value) cancel(); field = value }
    var onStarted: () -> Unit = {}
    var onRecognize: (GlideRequest) -> Unit = {}
    private val points = ArrayList<GlidePoint>()
    private var keys = emptyList<GlideKey>()
    private var startedAt = 0L
    private var originalKey: GlideKey? = null
    private var active = false
    private var swallowing = false
    private var eligible = false
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop * 2
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.google.android.material.color.MaterialColors.getColor(host.context,
            com.google.android.material.R.attr.colorPrimary, android.graphics.Color.BLUE)
        style = Paint.Style.STROKE
        strokeWidth = 3f * host.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** True consumes the event. The caller cancels its child stream when interception starts. */
    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                eligible = false
                if (active) { swallowing = true; active = false; points.clear(); host.invalidate() }
            }
            MotionEvent.ACTION_MOVE -> if (eligible) move(event)
            MotionEvent.ACTION_CANCEL -> { val consumed = active || swallowing; cancel(); return consumed }
            MotionEvent.ACTION_UP -> {
                val consumed = active || swallowing
                if (active && eligible && validEnd(event)) {
                    append(event.x, event.y, event.eventTime)
                    val selectedLayout = layout
                    val snapshot = points.toList()
                    val capturedKeys = keys
                    cancel()
                    if (selectedLayout != null && snapshot.size >= 2) {
                        onRecognize(GlideRequest(selectedLayout, snapshot, capturedKeys))
                    }
                } else cancel()
                return consumed
            }
        }
        return active || swallowing
    }

    private fun validEnd(event: MotionEvent) = event.eventTime - startedAt in 0..GlidePoint.MAX_DURATION_MILLIS &&
        event.x in 0f..host.width.toFloat() && event.y in 0f..host.height.toFloat()

    private fun begin(event: MotionEvent) {
        cancel()
        if (layout == null || host.width <= 0 || host.height <= 0) return
        keys = geometry()
        val x = event.x / host.width
        val y = event.y / host.height
        originalKey = keys.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
        eligible = originalKey != null
        if (eligible) {
            onStarted()
            startedAt = event.eventTime
            append(event.x, event.y, event.eventTime)
        }
    }

    private fun move(event: MotionEvent) {
        if (event.eventTime - startedAt > GlidePoint.MAX_DURATION_MILLIS ||
            event.x !in 0f..host.width.toFloat() || event.y !in 0f..host.height.toFloat()) {
            swallowing = active
            eligible = false; active = false; points.clear(); host.invalidate()
            return
        }
        val first = points.firstOrNull() ?: return
        val distance = hypot(event.x - first.x * host.width, event.y - first.y * host.height)
        if (!active && distance > slop) {
            if (event.eventTime - startedAt >= ViewConfiguration.getLongPressTimeout()) {
                eligible = false; points.clear(); return
            }
            active = true
        }
        for (index in 0 until minOf(event.historySize, 32)) {
            append(event.getHistoricalX(index), event.getHistoricalY(index), event.getHistoricalEventTime(index))
        }
        append(event.x, event.y, event.eventTime)
        if (active) host.invalidate()
    }

    private fun append(x: Float, y: Float, time: Long) {
        val elapsed = (time - startedAt).coerceIn(0L, GlidePoint.MAX_DURATION_MILLIS)
        if (points.lastOrNull()?.elapsedMillis?.let { it > elapsed } == true) return
        val next = GlidePoint((x / host.width).coerceIn(0f, 1f), (y / host.height).coerceIn(0f, 1f), elapsed)
        if (points.size < GlideRequest.MAX_POINTS) {
            points += next
        } else {
            // Resample the complete bounded stroke so the newest tail keeps
            // the same temporal density as the rest of the gesture.
            val combined = ArrayList<GlidePoint>(points.size + 1).apply {
                addAll(points)
                add(next)
            }
            val last = combined.lastIndex
            points.clear()
            repeat(GlideRequest.MAX_POINTS) { index ->
                points += combined[index * last / (GlideRequest.MAX_POINTS - 1)]
            }
        }
    }

    fun draw(canvas: Canvas) {
        if (!active || points.isEmpty()) return
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x * host.width, point.y * host.height)
            else path.lineTo(point.x * host.width, point.y * host.height)
        }
        canvas.drawPath(path, paint)
    }

    fun cancel() {
        active = false; eligible = false; swallowing = false
        points.clear(); keys = emptyList(); originalKey = null
        host.invalidate()
    }
}
