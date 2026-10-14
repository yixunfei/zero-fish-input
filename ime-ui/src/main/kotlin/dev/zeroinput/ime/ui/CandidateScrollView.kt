package dev.zeroinput.ime.ui

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.widget.HorizontalScrollView
import dev.zeroinput.engine.api.PageDirection

/** Uses native drag/fling; loading extends the content without consuming the gesture. */
internal class CandidateScrollView(context: Context) : HorizontalScrollView(context) {
    var onNearEdge: (PageDirection) -> Unit = {}
    var onBrowseStarted: () -> Unit = {}
    var canLoad: (PageDirection) -> Boolean = { false }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isSaveEnabled = false
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (canLoad(PageDirection.NEXT)) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                if (canLoad(PageDirection.PREVIOUS)) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            }
        })
    }

    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(x, y, oldX, oldY)
        checkEdge(if (x < oldX) PageDirection.PREVIOUS else PageDirection.NEXT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            checkEdge(PageDirection.NEXT)
            checkEdge(PageDirection.PREVIOUS)
        }
        return consumed
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onBrowseStarted()
        return super.dispatchTouchEvent(event)
    }

    fun checkEdge(direction: PageDirection) {
        if (!isShown || width == 0 || !canLoad(direction)) return
        val margin = (96 * resources.displayMetrics.density).toInt()
        val near = if (direction == PageDirection.NEXT) (getChildAt(0)?.width ?: 0) - width - scrollX <= margin
            else scrollX <= margin
        if (near) onNearEdge(direction)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (super.performAccessibilityAction(action, arguments)) return true
        val direction = when (action) {
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> PageDirection.NEXT
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> PageDirection.PREVIOUS
            else -> return false
        }
        if (!canLoad(direction)) return false
        onBrowseStarted()
        onNearEdge(direction)
        return true
    }
}
