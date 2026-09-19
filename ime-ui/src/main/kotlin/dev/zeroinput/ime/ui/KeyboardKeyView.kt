package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.InsetDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

/** Immediate, rectangular touch target; Android splits different pointers between keys. */
internal class KeyboardKeyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    private var holding = false
    private var revision = 0L
    private var gestureRevision: Long? = null
    private var action: KeyboardAction? = null
    internal val boundAction: KeyboardAction? get() = action
    private var longPressed = false
    private var palette: KeyPalette? = null
    private val longPress = Runnable { if (holding) longPressed = performLongClick() }
    var onAction: (KeyboardAction) -> Unit = {}

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        isSoundEffectsEnabled = false
        letterSpacing = 0f
        setPadding(0, 0, 0, 0)
        setOnClickListener { action?.let(onAction) }
    }

    fun bind(spec: KeySpec) {
        if (action != spec.action) { revision++; cancelTouch() }
        action = spec.action
        if (text != spec.label) text = spec.label
        contentDescription = spec.contentDescription
        isEnabled = spec.enabled
    }

    fun setColors(base: Int, pressed: Int, outline: Int, radius: Float, inset: Int) {
        val updated = KeyPalette(base, pressed, outline, radius, inset)
        if (palette == updated) return
        palette = updated
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), tile(pressed, outline, radius, inset))
            addState(intArrayOf(), tile(base, outline, radius, inset))
        }
    }

    override fun performClick(): Boolean {
        val current = gestureRevision == null || gestureRevision == revision
        gestureRevision = null
        if (!isEnabled || !current) return false
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true
                longPressed = false
                isPressed = true
                gestureRevision = revision
                if (isLongClickable) postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!inside(event)) cancelTouch()
            MotionEvent.ACTION_UP -> {
                val click = holding && !longPressed && inside(event)
                cancelTouch()
                // View's posted PerformClick can sit behind another frame and queued keys.
                if (click) performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    fun cancelTouch() { holding = false; isPressed = false; removeCallbacks(longPress) }

    override fun onDetachedFromWindow() { cancelTouch(); super.onDetachedFromWindow() }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    private fun inside(event: MotionEvent): Boolean = event.x >= 0 && event.y >= 0 && event.x < width && event.y < height

    private fun tile(color: Int, line: Int, radius: Float, inset: Int) = InsetDrawable(GradientDrawable().apply {
        setColor(color)
        setStroke(1, line)
        cornerRadius = radius
    }, inset)

    private data class KeyPalette(val base: Int, val pressed: Int, val outline: Int, val radius: Float, val inset: Int)
}
