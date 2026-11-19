package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** A labelled, focus-free draft surface. Only the host owns the actual input. */
internal class StegoFieldView(context: Context, label: Int, hint: Int) : LinearLayout(context) {
    private var expandedText = false
    private var fullyRevealed = false
    private var activeField = false
    private val selectedBackground by lazy { fieldBackground(true) }
    private val idleBackground by lazy { fieldBackground(false) }
    val title = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        setText(label); isAllCaps = false; minWidth = 0; minimumWidth = 0
        textSize = 13f; letterSpacing = 0f; insetTop = 0; insetBottom = 0
        maxLines = 2; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0); filterTouchesWhenObscured = true
    }
    private val editing = TextView(context).apply {
        setText(R.string.stego_editing); textSize = 12f; isSaveEnabled = false
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
    }
    private val expand = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        minWidth = 0; minimumWidth = 0; isAllCaps = false; textSize = 12f
        letterSpacing = 0f; insetTop = 0; insetBottom = 0; setPadding(0, 0, 0, 0)
        filterTouchesWhenObscured = true; visibility = GONE
        setOnClickListener { expandedText = !expandedText; renderExpansion() }
    }
    val body = DraftTextView(context).apply {
        textSize = 16f; minHeight = dp(64); maxLines = 3
        gravity = Gravity.TOP or Gravity.START
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setHint(hint)
        isClickable = true; isFocusable = false; isSaveEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        orientation = VERTICAL; isSaveEnabled = false
        addView(LinearLayout(context).apply {
            isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { title.minHeight = dp(48) })
            addView(editing, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(expand, LayoutParams(dp(48), dp(48)))
        })
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setPadding(0, 0, 0, dp(4))
    }

    fun render(value: StegoFieldUi?, active: Boolean, enabled: Boolean, expanded: Boolean) {
        val text = value?.text.orEmpty()
        if (body.text.toString() != text) body.text = text
        fullyRevealed = expanded; activeField = active
        expand.visibility = if (!expanded && (text.length > 48 || '\n' in text)) VISIBLE else GONE
        expand.isEnabled = enabled
        renderExpansion()
        body.isEnabled = enabled; title.isEnabled = enabled
        title.isSelected = active
        editing.visibility = if (active) VISIBLE else GONE
        val background = if (active) selectedBackground else idleBackground
        if (body.background !== background) body.background = background
        body.renderCaret(if (active && enabled) value?.caret else null, value?.preeditLength ?: 0)
    }

    private fun fieldBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(MaterialColors.getColor(body, com.google.android.material.R.attr.colorSurface))
        setStroke(dp(if (active) 2 else 1), MaterialColors.getColor(body,
            if (active) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutline))
    }

    private fun renderExpansion() {
        body.maxLines = if (fullyRevealed || expandedText) Int.MAX_VALUE else if (activeField) 5 else 2
        expand.setText(if (expandedText) R.string.stego_collapse_text else R.string.stego_expand_text)
        expand.contentDescription = context.getString(if (expandedText) R.string.stego_collapse_section else R.string.stego_expand_section,
            title.text)
    }

    fun clear() { body.text = ""; body.renderCaret(null); expandedText = false; expand.visibility = GONE }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
