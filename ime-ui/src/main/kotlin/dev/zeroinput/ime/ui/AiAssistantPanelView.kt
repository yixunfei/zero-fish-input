package dev.zeroinput.ime.ui

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.*

/** Full-screen user-authored chat. No editor insertion, page collection or clipboard ports. */
class AiAssistantPanelView(context: Context) : LinearLayout(context) {
    var onSubmit: (AiAction, String, String?) -> Unit = { _, _, _ -> }
    var onInterrupt: (AiAction, String, String?) -> Unit = { _, _, _ -> }
    var onQueueEdit: (Int) -> Unit = {}
    var onQueueRemove: (Int) -> Unit = {}
    var onCapabilities: () -> Unit = {}
    var onContextLibrary: () -> Unit = {}
    var onCompact: () -> Unit = {}
    var onSkills: () -> Unit = {}
    var onToolTask: () -> Unit = {}
    var onNativeAssistant: () -> Unit = {}
    var onUndoCompaction: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onNewConversation: () -> Unit = {}
    var onDeviceAgent: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onModelSelected: (String) -> Unit = {}
    var onReasoningChanged: (AiReasoningMode) -> Unit = {}
    var onConversationsRequested: () -> Unit = {}
    var onConversationSelected: (String) -> Unit = {}
    var onConversationDeleted: (String) -> Unit = {}
    var onConversationRename: (String, String) -> Unit = { _, _ -> }
    var onContextAction: (AiContextCommand) -> Unit = {}
    private var allowed = false
    private var streaming = false
    private var action = AiAction.ASK
    private var reasoning = AiReasoningMode.DEFAULT
    private var thinkingVisible = true
    private val answer = StringBuilder()
    private val thoughts = StringBuilder()
    private val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
    private val navigation = LinearLayout(context)
    private val body = ScrollView(context).apply { id = R.id.ai_assistant_body; isFillViewport = true }
    private val composer = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
    private val queueRows = LinearLayout(context).apply { orientation = VERTICAL }
    private var queueBusy = false
    private val readiness = label(14f)
    private val status = label(14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val workspace = button(R.string.ai_workspace_context) { onContextLibrary() }.apply { id = R.id.ai_workspace_context }
    private val transcript = label(15f)
    private val result = label(16f).apply { id = R.id.ai_assistant_result; setTextIsSelectable(true) }
    private val thinking = label(14f).apply { visibility = GONE }
    private val prompt = EditText(context).apply {
        id = R.id.ai_assistant_prompt
        hint = context.getString(R.string.ai_input_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(InputFilter.LengthFilter(AiLimits.MAX_INPUT_CHARS))
        minLines = 1
        maxLines = 3
        isSaveEnabled = false
    }
    private val send = button(R.string.ai_submit) {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) prompt.error = context.getString(R.string.ai_input_required)
        else if (allowed) onSubmit(action, text,
            if (action == AiAction.TRANSLATE) targetLanguage else null)
    }.apply { id = R.id.ai_assistant_send }
    private val interrupt = button(R.string.ai_queue_interrupt) {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) prompt.error = context.getString(R.string.ai_input_required)
        else if (allowed) onInterrupt(action, text, if (action == AiAction.TRANSLATE) targetLanguage else null)
    }.apply { id = R.id.ai_assistant_interrupt; visibility = GONE }
    private val stop = button(R.string.ai_cancel) { onCancel() }.apply { id = R.id.ai_assistant_stop }
    private val model: MaterialButton = button(R.string.ai_switch_model) { modelMenu.show() }.apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val modelMenu = AiModelMenu(model, { onModelSelected(it) }, { onSettings() })
    private val actionButton = button(R.string.ai_action_ask) { showActions() }
    private var targetLanguage = resources.getStringArray(R.array.ai_target_languages).first()
    private val targetButton: MaterialButton = button(R.string.ai_target_language_hint) {
        PopupMenu(context, targetButton).apply {
            val languages = resources.getStringArray(R.array.ai_target_languages)
            languages.forEachIndexed { index, language -> menu.add(0, index, index, language) }
            setOnMenuItemClickListener { item ->
                languages.getOrNull(item.itemId)?.let { targetLanguage = it; targetButton.text = it }
                true
            }
            show()
        }
    }.apply { visibility = GONE; text = targetLanguage }
    private val reasoningButton: MaterialButton = button(R.string.ai_reasoning_default) {
        reasoning = AiReasoningMode.entries[(reasoning.ordinal + 1) % AiReasoningMode.entries.size]
        reasoningButtonText()
        onReasoningChanged(reasoning)
    }
    private val history = AiConversationListView(context).apply {
        visibility = GONE
        onSelect = { onConversationSelected(it); visibility = GONE }
        onDelete = { onConversationDeleted(it) }
        onRename = { id, title -> onConversationRename(id, title) }
        onRefresh = { onConversationsRequested() }
    }
    private val contextRows = AiContextView(context).apply {
        visibility = GONE
        onHistory = { revision, index, included -> onContextAction(AiContextCommand.History(revision, index, included)) }
        onRecent = { onContextAction(AiContextCommand.Recent(it)) }
        onClear = { onContextAction(AiContextCommand.Clear(it)) }
        onRemove = { revision, index -> onContextAction(AiContextCommand.Remove(revision, index)) }
    }
    private val compact = button(R.string.ai_workspace_compact) { onCompact() }.apply { isEnabled = false }
    private val undoCompaction = button(R.string.ai_workspace_undo) { onUndoCompaction() }.apply { isEnabled = false }
    private val contextTools = row(compact, undoCompaction).apply { visibility = GONE }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        body.addView(content)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(composer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(label(24f).apply { setText(R.string.ai_assistant_title) })
        content.addView(label(14f).apply { setText(R.string.ai_assistant_notice) })
        content.addView(navigation.apply {
            orientation = HORIZONTAL
            listOf(
                button(R.string.ai_assistant_chat) {}.apply { isEnabled = false },
                button(R.string.ai_assistant_device) { onDeviceAgent() }.apply { id = R.id.ai_assistant_device },
                button(R.string.ai_assistant_settings) { onSettings() },
            ).forEach { addView(it.apply { maxLines = 2 }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        })
        content.addView(button(R.string.ai_capabilities) { onCapabilities() })
        content.addView(workspace)
        content.addView(button(R.string.ai_workspace_skills) { onSkills() })
        content.addView(button(R.string.ai_tool_task) { onToolTask() })
        content.addView(button(R.string.ai_native_assistant) { onNativeAssistant() })
        content.addView(readiness)
        content.addView(row(
            button(R.string.ai_new_conversation) { onNewConversation(); prompt.text?.clear() },
            button(R.string.ai_conversations_title) {
                history.visibility = if (history.visibility == GONE) VISIBLE else GONE
                if (history.visibility == VISIBLE) onConversationsRequested()
            },
            button(R.string.ai_context_title) {
                contextRows.visibility = if (contextRows.visibility == GONE) VISIBLE else GONE
                contextTools.visibility = contextRows.visibility
            },
        ))
        content.addView(history)
        content.addView(contextRows)
        content.addView(contextTools)
        content.addView(transcript)
        content.addView(thinking)
        content.addView(result)
        content.addView(status)
        content.addView(queueRows)
        composer.addView(prompt)
        content.addView(model, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(row(actionButton, targetButton, reasoningButton))
        composer.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(send, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(interrupt, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(stop, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        updateActions()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        prompt.maxLines = if (View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom < dp(400)) 1 else 3
        val stacked = resources.configuration.fontScale > 1.3f && View.MeasureSpec.getSize(widthMeasureSpec) < dp(480)
        if ((navigation.orientation == VERTICAL) != stacked) {
            navigation.orientation = if (stacked) VERTICAL else HORIZONTAL
            for (index in 0 until navigation.childCount) {
                navigation.getChildAt(index).layoutParams = LayoutParams(
                    if (stacked) LayoutParams.MATCH_PARENT else 0, LayoutParams.WRAP_CONTENT, if (stacked) 0f else 1f)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun ready(message: String, allowed: Boolean, showThinking: Boolean) {
        readiness.text = message
        this.allowed = allowed
        thinkingVisible = showThinking
        thinking.visibility = if (showThinking && thoughts.isNotEmpty()) VISIBLE else GONE
        updateActions()
    }

    fun render(event: AiStreamEvent) {
        when (event) {
            AiStreamEvent.Started -> {
                streaming = true
                answer.clear(); thoughts.clear(); result.text = ""; thinking.text = ""
                status.setText(R.string.ai_assistant_working)
            }
            is AiStreamEvent.Delta -> {
                answer.append(event.text.take((AiLimits.MAX_OUTPUT_CHARS - answer.length).coerceAtLeast(0)))
                thoughts.append(event.thinking.take((AiLimits.MAX_OUTPUT_CHARS - thoughts.length).coerceAtLeast(0)))
                result.text = answer
                thinking.text = thoughts
            }
            is AiStreamEvent.Completed -> {
                streaming = false
                answer.clear(); answer.append(event.text.take(AiLimits.MAX_OUTPUT_CHARS))
                thoughts.clear(); thoughts.append(event.thinking.take(AiLimits.MAX_OUTPUT_CHARS))
                result.text = answer.toString(); thinking.text = thoughts.toString()
                status.setText(R.string.ai_assistant_finished)
            }
            is AiStreamEvent.Failed -> { streaming = false; clearOutput(); status.setText(aiErrorMessage(event.error)) }
            AiStreamEvent.Cancelled -> { streaming = false; clearOutput(); status.setText(R.string.ai_assistant_stopped) }
        }
        thinking.visibility = if (thinkingVisible && thoughts.isNotEmpty()) VISIBLE else GONE
        updateActions()
    }

    fun renderConversation(value: AiConversation?) {
        history.current(value?.id)
        val messages = value?.messages.orEmpty()
        transcript.text = messages.dropLast(if (messages.lastOrNull()?.role == AiRole.ASSISTANT) 1 else 0).joinToString("\n\n") {
            context.getString(if (it.role == AiRole.USER) R.string.ai_user_message else R.string.ai_assistant_message, it.content)
        }
        if (value == null) { clearOutput(); status.text = "" }
        else if (!streaming) result.text = messages.lastOrNull { it.role == AiRole.ASSISTANT }?.content.orEmpty()
    }

    fun renderModels(models: List<String>, selected: String?) = modelMenu.render(models, selected)
    fun compactionState(available: Boolean, undoAvailable: Boolean) {
        compact.isEnabled = available
        undoCompaction.isEnabled = undoAvailable
    }
    fun renderContext(value: AiContextState) {
        contextRows.render(value)
        workspace.text = context.getString(R.string.ai_workspace_summary,
            value.workspace.persona?.name ?: context.getString(R.string.ai_workspace_default),
            value.workspace.memories.size, value.characterCount, AiReference.MAX_CONTEXT_CHARS, value.workspace.skills.size)
    }
    fun renderConversations(values: List<AiConversationSummary>) = history.render(values)
    fun renderHistoryStatus(saving: Boolean, loading: Boolean, failed: Boolean) = history.status(saving, loading, failed)

    fun accepted() { prompt.text?.clear() }
    fun queueFull() { prompt.error = context.getString(R.string.ai_queue_full) }
    fun editQueued(action: AiAction, text: String, target: String?) {
        this.action = action
        actionButton.setText(actionLabel(action))
        if (target != null) { targetLanguage = target; targetButton.text = target }
        targetButton.visibility = if (action == AiAction.TRANSLATE) VISIBLE else GONE
        prompt.setText(text)
        prompt.setSelection(prompt.length())
        prompt.requestFocus()
    }
    fun hasDraft() = prompt.text.isNotEmpty()
    fun renderQueue(messages: List<String>, busy: Boolean) {
        queueBusy = busy
        queueRows.removeAllViews()
        messages.forEachIndexed { index, text ->
            queueRows.addView(label(14f).apply { this.text = context.getString(R.string.ai_queue_item, index + 1, text.take(160)) })
            queueRows.addView(row(button(R.string.ai_queue_edit) { onQueueEdit(index) },
                button(R.string.ai_queue_remove) { onQueueRemove(index) }))
        }
        updateActions()
    }
    fun draftOccupied() { prompt.error = context.getString(R.string.ai_queue_draft_occupied) }

    fun clear() {
        streaming = false
        compactionState(false, false)
        contextTools.visibility = GONE
        renderQueue(emptyList(), false)
        prompt.text?.clear()
        transcript.text = ""
        clearOutput()
        modelMenu.dismiss()
    }

    private fun clearOutput() { answer.clear(); thoughts.clear(); result.text = ""; thinking.text = ""; thinking.visibility = GONE }
    private fun updateActions() {
        send.isEnabled = allowed
        send.setText(if (streaming || queueBusy) R.string.ai_queue_send else R.string.ai_submit)
        stop.isEnabled = streaming || queueBusy
        interrupt.visibility = if (streaming || queueBusy) VISIBLE else GONE
        interrupt.isEnabled = allowed
        model.isEnabled = allowed && !streaming && !queueBusy
    }
    private fun reasoningButtonText() = reasoningButton.setText(when (reasoning) {
        AiReasoningMode.DEFAULT -> R.string.ai_reasoning_default
        AiReasoningMode.FAST -> R.string.ai_reasoning_fast
        AiReasoningMode.DEEP -> R.string.ai_reasoning_deep
    })

    private fun showActions() {
        PopupMenu(context, actionButton).apply {
            AiAction.entries.forEach { menu.add(0, it.ordinal, it.ordinal, actionLabel(it)).isChecked = it == action }
            setOnMenuItemClickListener {
                AiAction.entries.getOrNull(it.itemId)?.let { selected ->
                    action = selected
                    actionButton.setText(actionLabel(selected))
                    targetButton.visibility = if (selected == AiAction.TRANSLATE) VISIBLE else GONE
                }
                true
            }
            show()
        }
    }

    private fun actionLabel(action: AiAction): Int = when (action) {
        AiAction.ASK -> R.string.ai_action_ask
        AiAction.PLAN -> R.string.ai_action_plan
        AiAction.POLISH -> R.string.ai_action_polish
        AiAction.REWRITE -> R.string.ai_action_rewrite
        AiAction.TRANSLATE -> R.string.ai_action_translate
    }
    private fun row(vararg views: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { orientation = HORIZONTAL; views.forEach { addView(it) } })
    }
    private fun label(size: Float) = TextView(context).apply { textSize = size; isSaveEnabled = false; setPadding(0, dp(6), 0, dp(6)) }
    private fun button(label: Int, action: () -> Unit) = MaterialButton(context).apply {
        setText(label); isAllCaps = false; minHeight = dp(48); isSaveEnabled = false; setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
