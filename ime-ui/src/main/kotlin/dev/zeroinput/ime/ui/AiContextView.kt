package dev.zeroinput.ime.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import dev.zeroinput.ai.api.*

/** Full, plain-text review of exactly the context the owner will submit. */
internal class AiContextView(context: Context) : LinearLayout(context) {
    var onHistory: (Long, Int, Boolean) -> Unit = { _, _, _ -> }
    var onRecent: (Long) -> Unit = {}
    var onClear: (Long) -> Unit = {}
    var onRemove: (Long, Int) -> Unit = { _, _ -> }
    private var state = AiContextState()
    private var preview = false

    init { orientation = VERTICAL; isSaveEnabled = false }

    fun closePreview() {
        if (!preview) return
        preview = false
        render(state)
    }

    fun render(value: AiContextState) {
        state = value
        removeAllViews()
        addView(label(context.getString(R.string.ai_context_count, value.references.size,
            value.selectedHistory.size, value.characterCount, AiReference.MAX_CONTEXT_CHARS)))
        addView(android.widget.HorizontalScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(button(if (preview) R.string.ai_context_choose else R.string.ai_context_preview) {
                    preview = !preview; render(state)
                })
                if (!preview) {
                    addView(button(R.string.ai_context_recent) { onRecent(value.revision) })
                    addView(button(R.string.ai_context_clear) { onClear(value.revision) })
                }
            })
        })
        value.references.forEachIndexed { index, reference ->
            addView(label(context.getString(R.string.ai_reference_number, index + 1)))
            addView(label(if (preview) reference.text else referencePreview(reference.text)))
            if (!preview) addView(button(R.string.ai_context_remove) { onRemove(value.revision, index) })
        }
        value.messages.forEachIndexed { index, message ->
            if (preview && index !in value.selectedHistory) return@forEachIndexed
            val text = context.getString(if (message.role == AiRole.USER) R.string.ai_user_message
                else R.string.ai_assistant_message, message.content)
            if (preview) addView(label(text))
            else if (message.role != AiRole.SYSTEM) addView(MaterialCheckBox(context).apply {
                this.text = text
                isChecked = index in value.selectedHistory
                isSaveEnabled = false
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> onHistory(value.revision, index, checked) }
            })
        }
        addView(label(context.getString(R.string.ai_context_transient)).apply { textSize = 12f })
        if (value.references.isEmpty() && (if (preview) value.selectedHistory.isEmpty() else value.messages.isEmpty())) {
            addView(label(context.getString(R.string.ai_context_empty)))
        }
    }

    private fun label(value: String) = TextView(context).apply { text = value; textSize = 15f; isSaveEnabled = false }
    private fun button(value: Int, action: () -> Unit) = MaterialButton(context).apply {
        setText(value); isAllCaps = false; letterSpacing = 0f; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
