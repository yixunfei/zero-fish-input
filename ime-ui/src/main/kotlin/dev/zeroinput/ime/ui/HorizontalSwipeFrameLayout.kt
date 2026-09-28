package dev.zeroinput.ime.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import dev.zeroinput.engine.api.PageDirection
import kotlin.math.abs

/** Owns a horizontal drag only after cancelling its child's pending click. */
internal class HorizontalSwipeFrameLayout(context: Context) : FrameLayout(context) {
    var canSwipe: (PageDirection) -> Boolean = { true }
    var onSwipe: (PageDirection) -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val distance = maxOf(slop * 2f, 48 * resources.displayMetrics.density)
    private var startX = 0f
    private var startY = 0f
    private var nextAllowed = false
    private var previousAllowed = false
    private var abandoned = false
    private var dragging = false

    init {
        // Empty grids have no child that consumes DOWN. Keep the gesture stream
        // on this surface while leaving accessibility focus on its controls.
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelSwipe()
            abandoned = false
            startX = event.x
            startY = event.y
            nextAllowed = canSwipe(PageDirection.NEXT)
            previousAllowed = canSwipe(PageDirection.PREVIOUS)
        }
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) abandoned = true
        val dx = event.x - startX
        val dy = event.y - startY
        val direction = if (dx < 0) PageDirection.NEXT else PageDirection.PREVIOUS
        if (!abandoned && !dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (abs(dy) > slop && abs(dy) >= abs(dx)) abandoned = true
            val allowed = if (direction == PageDirection.NEXT) nextAllowed else previousAllowed
            if (allowed && abs(dx) >= distance && abs(dx) > abs(dy) * 1.5f) {
                dragging = true
                MotionEvent.obtain(event).also { cancel ->
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        if (!dragging) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val allowed = if (direction == PageDirection.NEXT) nextAllowed else previousAllowed
            val invoke = !abandoned && allowed && abs(dx) >= distance && abs(dx) > abs(dy) * 1.5f
            cancelSwipe()
            if (invoke && canSwipe(direction)) onSwipe(direction)
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelSwipe()
        return true
    }

    fun cancelSwipe() {
        abandoned = true
        dragging = false
        nextAllowed = false
        previousAllowed = false
    }

    override fun onDetachedFromWindow() { cancelSwipe(); super.onDetachedFromWindow() }
}
