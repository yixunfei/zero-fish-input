package dev.zeroinput.ime.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.AiConversationSummary

internal class AiConversationListView(context: Context) : LinearLayout(context) {
    var onSelect: (String) -> Unit = {}
    var onDelete: (String) -> Unit = {}
    var onRename: (String, String) -> Unit = { _, _ -> }
    var onRefresh: () -> Unit = {}
    private var values = emptyList<AiConversationSummary>()
    private var current: String? = null
    private var pendingDelete: String? = null
    private var saving = false
    private var loading = false
    private var failed = false

    init { orientation = VERTICAL; isSaveEnabled = false }

    fun render(values: List<AiConversationSummary>) { this.values = values; rebuild() }
    fun current(id: String?) { current = id; pendingDelete = null; rebuild() }
    fun status(saving: Boolean, loading: Boolean, failed: Boolean) {
        this.saving = saving; this.loading = loading; this.failed = failed
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        addView(TextView(context).apply {
            setText(when {
                !saving -> R.string.ai_conversations_temporary
                loading -> R.string.ai_conversations_loading
                failed -> R.string.ai_conversations_failed
                values.isEmpty() -> R.string.ai_conversations_empty
                else -> R.string.ai_conversations_saved
            })
        })
        if (failed) addView(button(context.getString(R.string.ai_conversations_retry), onRefresh))
        if (!saving || loading || failed) return
        values.forEach { value ->
            addView(button(if (value.id == current) context.getString(R.string.ai_conversation_current, value.title)
                else value.title) { pendingDelete = null; onSelect(value.id) })
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(button(context.getString(R.string.ai_conversation_rename)) { onRename(value.id, value.title) },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(button(context.getString(if (pendingDelete == value.id) R.string.ai_confirm_delete
                    else R.string.ai_delete_conversation)) {
                    if (pendingDelete == value.id) { pendingDelete = null; onDelete(value.id) }
                    else { pendingDelete = value.id; rebuild() }
                }.apply { contentDescription = context.getString(R.string.ai_delete_named, value.title) },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(context).apply {
        text = label; isAllCaps = false; isSaveEnabled = false
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
}
