package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.drawable.toDrawable
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CandidateKind
import com.google.android.material.color.MaterialColors

internal class CandidateItemView(context: Context) : AppCompatTextView(context) {
    var onSelected: (Int) -> Unit = {}
    private var index = -1
    private var identity = ""
    private var identityText = ""
    private var bindingRevision = 0L
    private var touchRevision: Long? = null
    private var holding = false
    private val highlightColor: Int

    init {
        setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        highlightColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondaryContainer, 0xffd5eadf.toInt())
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), highlightColor.toDrawable())
            addState(intArrayOf(android.R.attr.state_selected), highlightColor.toDrawable())
            addState(intArrayOf(), Color.TRANSPARENT.toDrawable())
        }
        gravity = Gravity.CENTER
        textSize = 18f
        letterSpacing = 0f
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (index >= 0 && (touchRevision == null || touchRevision == bindingRevision)) onSelected(index)
        touchRevision = null
        return true
    }

    fun bind(candidate: Candidate, visibleIndex: Int, highlighted: Boolean) {
        if (identity != candidate.id || identityText != candidate.text || index != visibleIndex) bindingRevision++
        identity = candidate.id
        identityText = candidate.text
        index = visibleIndex
        val label = if (candidate.kind == CandidateKind.RELATED_READING)
            context.getString(R.string.related_candidate, candidate.text) else candidate.text
        if (text != label) text = label
        contentDescription = context.getString(R.string.candidate_description, candidate.text)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, candidate.comment.takeIf(String::isNotBlank))
        isSelected = highlighted
    }

    fun clear() {
        bindingRevision++
        identity = ""
        identityText = ""
        index = -1
        text = ""
        contentDescription = null
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchRevision = bindingRevision
                holding = true
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.x < 0 || event.y < 0 || event.x >= width || event.y >= height) {
                    holding = false
                    isPressed = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val click = holding
                holding = false
                isPressed = false
                if (click) performClick()
                touchRevision = null
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                holding = false
                isPressed = false
                touchRevision = null
            }
        }
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
