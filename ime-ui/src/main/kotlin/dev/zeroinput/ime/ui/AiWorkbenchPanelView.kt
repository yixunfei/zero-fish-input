package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.*

/** Display and explicit user intents only. All draft conversion is owned by the service. */
class AiWorkbenchPanelView(context: Context) : LinearLayout(context) {
    var onSubmit: (AiAction, String, String?) -> Unit = { _, _, _ -> }
    var onCancel: () -> Unit = {}
    var onInsert: (String) -> Unit = {}
    var onConversationSelected: (String) -> Unit = {}
    var onConversationDeleted: (String) -> Unit = {}
    var onNewConversation: () -> Unit = {}
    var onEditingChanged: (Boolean) -> Unit = {}
    private var action = AiAction.ASK
    private var languageIndex = 0
    private var pendingDelete: String? = null
    private var streaming = false
    var editing = false
        private set

    private val draft = textView().apply {
        maxLines = 2
        minHeight = dp(48)
        hint = context.getString(R.string.ai_input_hint)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.ai_edit)
        setOnClickListener { setEditing(true) }
    }
    private val transcript = textView()
    private val result = textView()
    private val conversationRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val detailContent = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(conversationRows)
        addView(transcript)
        addView(result)
    }
    private val detail = ScrollView(context).apply { addView(detailContent); isFillViewport = true }
    private val target = button(R.string.ai_target_language_hint) { cycleLanguage() }
    private val edit = button(R.string.ai_edit) { setEditing(!editing) }
    private val submit = button(R.string.ai_submit) { submit() }
    private val cancel = button(R.string.ai_cancel) { onCancel() }
    private val insert = button(R.string.ai_insert) { onInsert("") }
    private val actions = AiAction.entries.associateWith { value ->
        button(actionLabel(value)) {
            action = value
            updateActionStyles()
            if (value == AiAction.TRANSLATE && draft.text.isNotBlank()) submit()
        }
    }

    init {
        orientation = VERTICAL
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        setPadding(dp(8), 0, dp(8), 0)
        addView(scrollRow(actions.values + target), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(draft, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(scrollRow(listOf(edit, submit, cancel, insert)), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        renderConversations(emptyList())
        updateActionStyles()
        render(AiStreamEvent.Cancelled)
    }

    fun renderDraft(value: String) { draft.text = value }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        detail.visibility = if (value) GONE else VISIBLE
        edit.setText(if (value) R.string.ai_read else R.string.ai_edit)
        onEditingChanged(value)
    }

    fun renderConversations(values: List<AiConversationSummary>) {
        conversationRows.removeAllViews()
        conversationRows.addView(button(R.string.ai_new_conversation) {
            pendingDelete = null
            onNewConversation()
            setEditing(true)
        })
        values.take(AiLimits.MAX_CONVERSATIONS).forEach { value ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.addView(buttonText(value.title) {
                pendingDelete = null
                onConversationSelected(value.id)
                setEditing(false)
            }, LayoutParams(0, dp(48), 1f))
            row.addView(button(R.string.ai_delete_conversation) {
                if (pendingDelete == value.id) {
                    pendingDelete = null
                    onConversationDeleted(value.id)
                } else {
                    pendingDelete = value.id
                    renderConversations(values)
                }
            }.apply {
                if (pendingDelete == value.id) setText(R.string.ai_confirm_delete)
                contentDescription = context.getString(R.string.ai_delete_named, value.title)
            })
            conversationRows.addView(row)
        }
    }

    fun renderConversation(value: AiConversation?) {
        result.text = ""
        transcript.text = value?.messages.orEmpty().joinToString("\n\n") { message ->
            context.getString(if (message.role == AiRole.USER) R.string.ai_user_message else R.string.ai_assistant_message,
                message.content)
        }
        if (value == null) result.text = ""
    }

    fun render(event: AiStreamEvent) {
        when (event) {
            AiStreamEvent.Started -> {
                streaming = true
                result.text = ""
                setEditing(false)
                submit.isEnabled = false
                cancel.isEnabled = true
                insert.isEnabled = false
            }
            is AiStreamEvent.Delta -> result.append(event.text)
            is AiStreamEvent.Completed -> {
                streaming = false
                result.text = event.text
                submit.isEnabled = true
                cancel.isEnabled = false
                insert.isEnabled = event.text.isNotBlank()
            }
            is AiStreamEvent.Failed -> {
                streaming = false
                setEditing(false)
                result.setText(when (event.error) {
                    is AiProviderError.Policy -> R.string.ai_policy_unavailable
                    is AiProviderError.Configuration -> R.string.ai_configuration_invalid
                    is AiProviderError.Network -> R.string.ai_connection_failed
                    is AiProviderError.Response -> R.string.ai_response_invalid
                })
                submit.isEnabled = true
                cancel.isEnabled = false
                insert.isEnabled = false
            }
            AiStreamEvent.Cancelled -> {
                streaming = false
                result.text = ""
                submit.isEnabled = true
                cancel.isEnabled = false
                insert.isEnabled = false
            }
        }
    }

    fun reset() {
        draft.text = ""
        transcript.text = ""
        pendingDelete = null
        languageIndex = 0
        action = AiAction.ASK
        updateActionStyles()
        renderConversations(emptyList())
        render(AiStreamEvent.Cancelled)
        setEditing(false)
    }

    private fun submit() {
        if (streaming) return
        val text = draft.text.toString().trim()
        if (text.isBlank()) { draft.error = context.getString(R.string.ai_input_required); return }
        draft.error = null
        val language = resources.getStringArray(R.array.ai_target_languages)[languageIndex]
        onSubmit(action, text, language)
    }

    private fun cycleLanguage() {
        val languages = resources.getStringArray(R.array.ai_target_languages)
        languageIndex = (languageIndex + 1) % languages.size
        updateActionStyles()
    }

    private fun updateActionStyles() {
        actions.forEach { (value, view) ->
            view.isSelected = value == action
            view.alpha = if (value == action) 1f else 0.62f
        }
        target.visibility = if (action == AiAction.TRANSLATE) VISIBLE else GONE
        target.text = resources.getStringArray(R.array.ai_target_languages)[languageIndex]
    }

    private fun scrollRow(views: Collection<View>) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { orientation = HORIZONTAL; views.forEach { addView(it) } })
    }

    private fun textView() = TextView(context).apply {
        textSize = 15f
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        isSaveEnabled = false
        setTextIsSelectable(false)
    }

    private fun button(label: Int, action: () -> Unit) = buttonText(context.getString(label), action)

    private fun buttonText(label: String, action: () -> Unit) = MaterialButton(context).apply {
        text = label
        isAllCaps = false
        letterSpacing = 0f
        minWidth = 0
        minimumWidth = 0
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setOnClickListener { action() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
    }

    private fun actionLabel(value: AiAction): Int = when (value) {
        AiAction.ASK -> R.string.ai_action_ask
        AiAction.PLAN -> R.string.ai_action_plan
        AiAction.POLISH -> R.string.ai_action_polish
        AiAction.REWRITE -> R.string.ai_action_rewrite
        AiAction.TRANSLATE -> R.string.ai_action_translate
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return values.getColor(0, fallback).also { values.recycle() }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
