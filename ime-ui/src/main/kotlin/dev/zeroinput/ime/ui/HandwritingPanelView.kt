package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** Single-character writing surface and explicit edit/commit controls. */
internal class HandwritingPanelView(context: Context) : LinearLayout(context) {
    var onStrokesChanged: (List<FloatArray>) -> Unit = {}
    var onCandidateSelected: (String) -> Boolean = { false }
    var onEditAction: (KeyboardAction) -> Unit = {}
    private val canvas = HandwritingCanvasView(context).apply { onChanged = { strokes ->
        renderCandidates(emptyList())
        onStrokesChanged(strokes)
    } }
    private val candidateRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val candidateScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(candidateRow)
    }
    private var candidates: List<String> = emptyList()

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        addView(candidateScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(controls(), LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        renderCandidates(emptyList())
    }

    private fun controls(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(button(R.string.handwriting_undo) { canvas.undo() }, weighted())
        addView(button(R.string.handwriting_clear) { canvas.clear() }, weighted())
        addView(button(R.string.handwriting_delete) {
            if (!canvas.undo()) onEditAction(KeyboardAction.Backspace)
        }, weighted())
        addView(button(R.string.handwriting_space) { selectOrEdit(KeyboardAction.Space) }, weighted())
        addView(button(R.string.handwriting_enter) { selectOrEdit(KeyboardAction.Enter) }, weighted())
    }

    private fun weighted() = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)

    private fun button(label: Int, action: () -> Unit) = MaterialButton(context).apply {
        text = context.getString(label)
        contentDescription = text
        textSize = 13f
        letterSpacing = 0f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun selectOrEdit(action: KeyboardAction) {
        val first = candidates.firstOrNull()
        if (first == null) {
            if (!canvas.hasStrokes()) onEditAction(action)
        } else if (onCandidateSelected(first)) onEditAction(action)
    }

    fun renderCandidates(values: List<String>, completed: Boolean = false) {
        candidates = values.take(16)
        candidateRow.removeAllViews()
        candidateScroll.scrollTo(0, 0)
        if (candidates.isEmpty()) {
            candidateRow.addView(TextView(context).apply {
                text = context.getString(when {
                    !canvas.hasStrokes() -> R.string.handwriting_hint
                    completed -> R.string.handwriting_no_match
                    else -> R.string.handwriting_recognizing
                })
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                setPadding(dp(12), 0, dp(12), 0)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            return
        }
        candidates.forEach { value ->
            candidateRow.addView(buttonForCandidate(value), LayoutParams(dp(54), dp(48)))
        }
    }

    fun showFailure() {
        candidates = emptyList()
        candidateRow.removeAllViews()
        candidateRow.addView(TextView(context).apply {
            text = context.getString(R.string.handwriting_unavailable)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
    }

    private fun buttonForCandidate(value: String) = button(R.string.handwriting_candidate) {
        onCandidateSelected(value)
    }.apply {
        text = value
        contentDescription = context.getString(R.string.handwriting_candidate_value, value)
        textSize = 22f
    }

    fun clear() { canvas.clear() }
    fun containsCandidate(value: String): Boolean = value in candidates && canvas.hasStrokes()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
