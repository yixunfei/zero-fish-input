package dev.zeroinput.ime.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Window furniture has its own gesture ownership, independent of typing and handwriting. */
internal class KeyboardPlacementControls(context: Context) : LinearLayout(context) {
    var onDock: () -> Unit = {}
    var onFlip: () -> Unit = {}
    var onMinimize: () -> Unit = {}
    var onMove: (Float, Float, Boolean) -> Unit = { _, _, _ -> }
    var onResize: (Float, Float, Boolean) -> Unit = { _, _, _ -> }
    var onGestureStart: () -> Unit = {}
    var onAccessibleAdjust: (Boolean, Int) -> Unit = { _, _ -> }
    var onAccessibleMoveHorizontal: (Int) -> Unit = {}
    private val move = button("↔", R.string.keyboard_move)
    private val flip = button("⇄", R.string.keyboard_change_hand)
    private val minimize = button("−", R.string.keyboard_minimize)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(button("▤", R.string.keyboard_dock).apply { setOnClickListener { onDock() } })
        addView(move, LayoutParams(0, dp(48), 1f))
        addView(flip.apply { setOnClickListener { onFlip() } })
        addView(minimize.apply { setOnClickListener { onMinimize() } })
        addView(button("⤡", R.string.keyboard_resize).apply {
            setOnTouchListener(DragListener { x, y, done -> onResize(x, y, done) })
            accessibleAdjustment(this, false)
        })
        move.setOnTouchListener(DragListener { x, y, done -> onMove(x, y, done) })
        accessibleAdjustment(move, true)
        ViewCompat.replaceAccessibilityAction(move, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT,
            context.getString(R.string.keyboard_move_left)) { _, _ ->
            onAccessibleMoveHorizontal(-1); true
        }
        ViewCompat.replaceAccessibilityAction(move, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT,
            context.getString(R.string.keyboard_move_right)) { _, _ ->
            onAccessibleMoveHorizontal(1); true
        }
    }

    fun render(mode: KeyboardPlacementMode) {
        move.isEnabled = mode == KeyboardPlacementMode.FLOATING
        flip.visibility = if (mode == KeyboardPlacementMode.FLOATING) GONE else VISIBLE
        minimize.visibility = if (mode == KeyboardPlacementMode.FLOATING) VISIBLE else GONE
    }

    private fun accessibleAdjustment(view: View, moving: Boolean) {
        ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
            context.getString(if (moving) R.string.keyboard_move_down else R.string.keyboard_enlarge)) { _, _ ->
            onAccessibleAdjust(moving, 1); true
        }
        ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
            context.getString(if (moving) R.string.keyboard_move_up else R.string.keyboard_shrink)) { _, _ ->
            onAccessibleAdjust(moving, -1); true
        }
    }

    private fun button(label: String, description: Int) = TextView(context).apply {
        text = label
        contentDescription = context.getString(description)
        textSize = 20f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        layoutParams = LayoutParams(dp(48), dp(48))
        setTextColor(com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK))
    }

    private inner class DragListener(val action: (Float, Float, Boolean) -> Unit) : OnTouchListener {
        private var x = 0f
        private var y = 0f
        private var active = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX; y = event.rawY; active = true
                    onGestureStart()
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (active) action(event.rawX - x, event.rawY - y, false)
                MotionEvent.ACTION_UP -> if (active) {
                    active = false
                    action(event.rawX - x, event.rawY - y, true)
                    view.performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (active) action(0f, 0f, true)
                    active = false
                }
            }
            return true
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
