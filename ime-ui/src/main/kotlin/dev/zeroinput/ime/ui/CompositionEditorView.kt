package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.appcompat.widget.AppCompatTextView
import dev.zeroinput.engine.api.CompositionSelection
import dev.zeroinput.engine.api.EngineSnapshot

/** A focus-free editor for engine input; it never opens Android's text/clipboard menu. */
internal class CompositionEditorView(context: Context) : AppCompatTextView(context) {
    var onSelection: (CompositionSelection, String) -> Unit = { _, _ -> }
    private var input = ""
    private var editableText = ""
    private var rawOffsets = intArrayOf(0)
    private var displayOffsets = intArrayOf(0)
    private var selection: CompositionSelection? = null
    private var preview: CompositionSelection? = null
    private var touching = false
    private var selecting = false
    private var anchor = 0
    private var downX = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hold = Runnable {
        if (touching) {
            selecting = true
            anchor = anchor.coerceAtMost((input.length - 1).coerceAtLeast(0))
            preview = CompositionSelection(anchor, (anchor + 1).coerceAtMost(input.length))
            invalidate()
        }
    }

    init {
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine()
        setHorizontallyScrolling(true)
        setPadding(dp(8), 0, dp(8), 0)
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 29) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        isFocusable = false
        setTextIsSelectable(false)
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                selection?.let {
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_SELECTION)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
                    info.movementGranularities = AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER
                    info.setTextSelection(displayOffsets[it.lower], displayOffsets[it.upper])
                }
            }
        })
    }

    fun render(snapshot: EngineSnapshot, label: String) {
        val next = snapshot.selection?.takeIf { snapshot.isComposing && it.isValidFor(snapshot.rawInput) }
        val candidate = snapshot.candidates.getOrNull(snapshot.highlightedIndex)
        val match = candidate?.pinyinMatch?.takeIf {
            it.start in snapshot.rawInput.indices && it.end in (it.start + 1)..snapshot.rawInput.length &&
                it.spelling.replace(" ", "") == snapshot.rawInput.substring(it.start, it.end)
        }
        val editable = when {
            next == null -> snapshot.rawInput
            match != null -> {
                val matched = listOf(snapshot.rawInput.take(match.start), match.spelling).filter(String::isNotEmpty).joinToString(" ")
                if (match.end < snapshot.rawInput.length) "$matched · ${snapshot.rawInput.drop(match.end)}" else matched
            }
            label.replace(" ", "") == snapshot.rawInput -> label
            else -> snapshot.rawInput
        }
        if (input != snapshot.rawInput || selection != next || editableText != editable) cancelGesture()
        if (input != snapshot.rawInput || editableText != editable) setOffsets(snapshot.rawInput, editable)
        input = snapshot.rawInput
        editableText = editable
        selection = next
        val reading = candidate?.comment.orEmpty()
        val display = when {
            next == null -> label
            match != null && reading.isNotEmpty() && match.spelling.replace('\'', ' ').trimEnd() != reading ->
                context.getString(R.string.pinyin_matched_reading, editable, reading)
            else -> editable
        }
        if (text.toString() != display) text = display
        isClickable = next != null
        val announced = if (match != null && match.end < input.length)
            context.getString(R.string.pinyin_remaining_input, display) else display
        contentDescription = if (next != null) context.getString(R.string.pinyin_edit_description, announced) else announced
        if (next != null) post { revealCaret() } else scrollTo(0, 0)
        invalidate()
    }

    private fun setOffsets(raw: String, display: String) {
        rawOffsets = IntArray(display.length + 1)
        displayOffsets = IntArray(raw.length + 1)
        var offset = 0
        for (index in display.indices) {
            if (raw.getOrNull(offset) == display[index]) offset++
            rawOffsets[index + 1] = offset
            displayOffsets[offset] = index + 1
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selection == null) return false
        if (event.pointerCount > 1) { cancelGesture(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                downX = event.x
                anchor = offset(event)
                val range = selection
                selecting = range != null && range.start != range.end &&
                    (anchor == range.start || anchor == range.end)
                if (selecting) anchor = if (anchor == range?.start) range.end else requireNotNull(range).start
                preview = CompositionSelection(anchor, offset(event))
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!selecting) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                if (kotlin.math.abs(event.x - downX) > slop) removeCallbacks(hold)
                val position = offset(event)
                preview = if (selecting) CompositionSelection(anchor, position) else CompositionSelection(position)
                revealCaret()
            }
            MotionEvent.ACTION_UP -> if (touching) {
                removeCallbacks(hold)
                performClick()
                cancelGesture()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val target = preview ?: selection ?: return false
        onSelection(target, input)
        return true
    }

    fun cancelGesture() {
        touching = false
        selecting = false
        preview = null
        removeCallbacks(hold)
        invalidate()
    }

    private fun offset(event: MotionEvent): Int =
        rawOffsets[getOffsetForPosition(event.x, event.y).coerceIn(0, editableText.length)]

    private fun revealCaret() {
        val range = preview ?: selection ?: return
        val textLayout = layout ?: return
        val x = textLayout.getPrimaryHorizontal(displayOffsets[range.end])
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val target = when {
            x < scrollX -> x.toInt()
            x > scrollX + available - dp(2) -> (x - available + dp(2)).toInt()
            else -> scrollX
        }
        scrollTo(target.coerceAtLeast(0), 0)
    }

    override fun onDraw(canvas: Canvas) {
        val range = preview ?: selection
        val textLayout = layout
        if (range != null && textLayout != null && range.isValidFor(input)) {
            val top = totalPaddingTop.toFloat()
            val bottom = top + textLayout.height
            val start = totalPaddingLeft + textLayout.getPrimaryHorizontal(displayOffsets[range.lower])
            val end = totalPaddingLeft + textLayout.getPrimaryHorizontal(displayOffsets[range.upper])
            marker.color = currentTextColor
            marker.alpha = if (range.start == range.end) 255 else 48
            canvas.drawRect(start, top, if (range.start == range.end) start + dp(2) else end, bottom, marker)
        }
        super.onDraw(canvas)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val current = selection ?: return super.performAccessibilityAction(action, arguments)
        val target = when (action) {
            AccessibilityNodeInfoCompat.ACTION_SET_SELECTION -> CompositionSelection(
                rawOffsets.getOrNull(arguments?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, -1)
                    ?: -1) ?: return false,
                rawOffsets.getOrNull(arguments?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, -1)
                    ?: -1) ?: return false)
            AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> {
                val step = if (action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY) 1 else -1
                val end = (current.end + step).coerceIn(0, input.length)
                CompositionSelection(if (arguments?.getBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN) == true)
                    current.start else end, end)
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (!target.isValidFor(input)) return false
        onSelection(target, input)
        return true
    }

    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelGesture()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
