package dev.zeroinput.ime.ui

import android.content.Context
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.zeroinput.engine.api.GlideCandidate

/** Explicit choices precede engine conversion; stale holders cannot submit replacement results. */
internal class GlideSuggestionsView(context: Context) : LinearLayout(context) {
    var onSelected: (GlideCandidate) -> Unit = {}
    var onCancelled: () -> Unit = {}
    private var revision = 0L
    private var candidates = emptyList<GlideCandidate>()
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.glide_cancel)
            isClickable = true; isFocusable = true
            setOnClickListener { onCancelled() }
        }, LayoutParams(dp(48), dp(48)))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun render(values: List<GlideCandidate>, busy: Boolean = false, failed: Boolean = false) {
        clear()
        candidates = values.take(16).toList()
        val ticket = revision
        if (values.isEmpty()) row.addView(label(context.getString(when {
            busy -> R.string.glide_recognizing
            failed -> R.string.glide_unavailable
            else -> R.string.glide_no_match
        })))
        candidates.forEach { candidate ->
            row.addView(label(candidate.displayText).apply {
                contentDescription = context.getString(R.string.glide_choose, candidate.displayText)
                isClickable = true; isFocusable = true
                setOnClickListener { if (ticket == revision && candidate in candidates) onSelected(candidate) }
            })
        }
    }

    fun clear() { revision++; candidates = emptyList(); row.removeAllViews() }
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 18f; gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(48); minWidth = dp(48)
        setTextColor(com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
