package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ai.api.*

/** Display and explicit user intents only. All draft conversion is owned by the service. */
class AiWorkbenchPanelView(context: Context) : LinearLayout(context) {
    var onSubmit: (AiAction, String, String?) -> Unit = { _, _, _ -> }
    var onCancel: () -> Unit = {}
    var onInsert: (String) -> Unit = {}
    var onConversationSelected: (String) -> Unit = {}
    var onConversationDeleted: (String) -> Unit = {}
    var onNewConversation: () -> Unit = {}
    var onConversationsRequested: () -> Unit = {}
    var onConversationRename: (String, String) -> Unit = { _, _ -> }
    var onRenameCancelled: () -> Unit = {}
    var onContextAction: (AiContextCommand) -> Unit = {}
    var onPageRequested: () -> Unit = {}
    var onPageSelected: (List<Int>) -> Unit = {}
    var onPageCancelled: () -> Unit = {}
    private var renaming = false
    private var detailMode = DetailMode.RESULT
    private enum class DetailMode { RESULT, CONTEXT, CONVERSATIONS, PAGE }
    var onModelSelected: (String) -> Unit = {}
    var onEditingChanged: (Boolean) -> Unit = {}
    var onSettings: () -> Unit = {}
    var onAddContent: () -> Unit = {}
    var onImportContent: () -> Unit = {}
    var onRemoveAttachment: (Int) -> Unit = {}
    private var action = AiAction.ASK
    private var languageIndex = 0
    private var streaming = false
    private var inputLanguage = InputLanguage.CHINESE
    var editing = false
        private set
    val editingHeightDp: Int get() = if (attachmentRow.childCount > 0) 272 else 224

    private val inputStatus = textView().apply { textSize = 12f; gravity = Gravity.CENTER_VERTICAL }
    private val model: MaterialButton = button(R.string.ai_switch_model) { modelMenu.show() }.apply {
        id = R.id.ai_model_button
    }
    private val modelMenu: AiModelMenu = AiModelMenu(model, { onModelSelected(it); setEditing(true) }, { onSettings() })
    private val newConversation = button(R.string.ai_new_conversation) {
        onNewConversation()
        setEditing(true)
    }

    private val draft = textView().apply {
        id = R.id.ai_draft_text
        maxLines = 2
        minHeight = dp(48)
        hint = context.getString(R.string.ai_input_hint)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.ai_edit)
        setOnClickListener { setEditing(true) }
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer, Color.TRANSPARENT))
            setStroke(dp(1), resolveColor(com.google.android.material.R.attr.colorOutline, Color.GRAY))
        }
    }
    private val transcript = textView()
    private val result = textView().apply { id = R.id.ai_result_text }
    private val conversationRows = AiConversationListView(context).apply {
        onSelect = { onConversationSelected(it); showDetail(DetailMode.RESULT) }
        onDelete = { onConversationDeleted(it) }
        onRename = { id, title -> onConversationRename(id, title) }
        onRefresh = { onConversationsRequested() }
    }
    private val contextRows = AiContextView(context).apply {
        onHistory = { revision, index, included -> onContextAction(AiContextCommand.History(revision, index, included)) }
        onRecent = { onContextAction(AiContextCommand.Recent(it)) }
        onClear = { onContextAction(AiContextCommand.Clear(it)) }
        onRemove = { revision, index -> onContextAction(AiContextCommand.Remove(revision, index)) }
    }
    private val pageRows = AiPageSelectionView(context).apply {
        onSelectionChanged = { selected -> if (detailMode == DetailMode.PAGE) submit.isEnabled = selected }
    }
    private val answerButton = button(R.string.ai_show_result) { showDetail(DetailMode.RESULT) }
    private val contextButton = button(R.string.ai_context_title) { showDetail(DetailMode.CONTEXT) }
    private val pageButton = button(R.string.ai_page_reference) { onPageRequested() }
    private val conversationsButton = button(R.string.ai_conversations_title) {
        showDetail(DetailMode.CONVERSATIONS); onConversationsRequested()
    }
    private val renameCancel = button(R.string.ai_rename_cancel) { onRenameCancelled() }.apply { visibility = GONE }
    private val detailContent = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(conversationRows)
        addView(contextRows)
        addView(pageRows)
        addView(transcript)
        addView(result)
    }
    private val detail = ScrollView(context).apply { addView(detailContent); isFillViewport = true }
    private val target = button(R.string.ai_target_language_hint) { cycleLanguage() }
    private val edit = button(R.string.ai_edit) {
        if (detailMode == DetailMode.PAGE) { onPageCancelled(); clearPage() }
        else setEditing(!editing)
    }
    private val submit = button(R.string.ai_submit) { submit() }
    private val cancel = button(R.string.ai_cancel) { onCancel() }
    private val insert = button(R.string.ai_insert) { onInsert(result.text.toString()) }
    private val settings = button(R.string.ai_settings) { onSettings() }
    private val addContent = button(R.string.ai_add_content) { onAddContent() }
    private val importContent = button(R.string.ai_import_content) { onImportContent() }
    private val more = panelIconButton(context, R.drawable.ic_keyboard_tools, R.string.ai_more) { showMore() }
    private val attachmentRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val actions = AiAction.entries.associateWith { value ->
        button(actionLabel(value)) {
            action = value
            updateActionStyles()
        }
    }

    init {
        orientation = VERTICAL
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        setPadding(dp(8), 0, dp(8), 0)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(model, LayoutParams(0, dp(48), 1f))
            addView(newConversation, LayoutParams(dp((88 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()), dp(48)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(scrollRow(listOf(pageButton, contextButton, conversationsButton, answerButton, renameCancel) +
            actions.values + target), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(inputStatus, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        addView(draft, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(HorizontalScrollView(context).apply { addView(attachmentRow) })
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf(submit, edit, insert).forEach { addView(it, LayoutParams(0, dp(48), 1f)) }
            addView(more, LayoutParams(dp(48), dp(48)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        cancel.visibility = GONE
        detailContent.addView(cancel, 0)
        importContent.visibility = GONE
        renderConversations(emptyList())
        updateActionStyles()
        render(AiStreamEvent.Cancelled)
        updateDetailVisibility()
    }

    fun renderDraft(value: String, language: InputLanguage = inputLanguage) {
        draft.text = value
        draft.error = null
        inputLanguage = language
        renderInputStatus()
        refreshCommands()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Short landscape panels must leave a scrollable reading area below the controls.
        val compactReading = !editing && (detailMode != DetailMode.RESULT ||
            MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(heightMeasureSpec) < dp(320))
        draft.visibility = if (compactReading) GONE else VISIBLE
        inputStatus.visibility = if (compactReading) GONE else VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun renderImportedContent(available: Boolean, names: List<String>) {
        importContent.visibility = if (available) VISIBLE else GONE
        attachmentRow.removeAllViews()
        names.forEachIndexed { index, name ->
            attachmentRow.addView(buttonText(context.getString(R.string.ai_remove_attachment, name)) {
                onRemoveAttachment(index)
            })
        }
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        if (value && detailMode == DetailMode.PAGE) {
            onPageCancelled()
            pageRows.render(null)
            detailMode = DetailMode.RESULT
            updateDetailVisibility()
        }
        editing = value
        detail.visibility = if (value) GONE else VISIBLE
        refreshCommands()
        renderInputStatus()
        onEditingChanged(value)
    }

    fun renderConversations(values: List<AiConversationSummary>) { conversationRows.render(values) }

    fun renderHistoryStatus(saving: Boolean, loading: Boolean, failed: Boolean) {
        conversationRows.status(saving, loading, failed)
    }

    fun renderContext(value: AiContextState) {
        contextRows.render(value)
        contextButton.text = context.getString(R.string.ai_context_short_count,
            value.references.size + value.selectedHistory.size)
    }

    fun renderPage(texts: List<String>?, incomplete: Boolean, loading: Boolean) {
        pageRows.render(texts, incomplete, loading)
        showDetail(DetailMode.PAGE)
    }

    fun clearPage() {
        pageRows.render(null)
        if (detailMode == DetailMode.PAGE) showDetail(DetailMode.CONTEXT)
    }

    fun renderRenaming(value: Boolean) {
        renaming = value
        refreshCommands()
        renameCancel.visibility = if (value) VISIBLE else GONE
        pageButton.isEnabled = !value
        contextButton.isEnabled = !value
        conversationsButton.isEnabled = !value
        actions.values.forEach { it.isEnabled = !value }
        target.isEnabled = !value
        renderInputStatus()
        if (value) setEditing(true)
    }

    private fun showDetail(mode: DetailMode) {
        if (detailMode == DetailMode.PAGE && mode != DetailMode.PAGE) {
            onPageCancelled()
            pageRows.render(null)
        }
        detailMode = mode
        setEditing(false)
        updateDetailVisibility()
    }

    private fun updateDetailVisibility() {
        conversationRows.visibility = if (detailMode == DetailMode.CONVERSATIONS) VISIBLE else GONE
        contextRows.visibility = if (detailMode == DetailMode.CONTEXT) VISIBLE else GONE
        pageRows.visibility = if (detailMode == DetailMode.PAGE) VISIBLE else GONE
        transcript.visibility = if (detailMode == DetailMode.RESULT) VISIBLE else GONE
        result.visibility = if (detailMode == DetailMode.RESULT) VISIBLE else GONE
        refreshCommands()
        requestLayout()
    }

    fun renderConversation(value: AiConversation?) {
        conversationRows.current(value?.id)
        val messages = value?.messages.orEmpty()
        val latest = messages.lastOrNull()?.takeIf { it.role == AiRole.ASSISTANT }
        result.text = latest?.content.orEmpty()
        insert.isEnabled = false
        transcript.text = (if (latest == null) messages else messages.dropLast(1)).joinToString("\n\n") { message ->
            context.getString(if (message.role == AiRole.USER) R.string.ai_user_message else R.string.ai_assistant_message,
                message.content)
        }
    }

    fun renderModels(models: List<String>, selected: String?) { modelMenu.render(models, selected) }

    fun render(event: AiStreamEvent) {
        when (event) {
            AiStreamEvent.Started -> {
                streaming = true
                result.text = ""
                showDetail(DetailMode.RESULT)
                submit.isEnabled = false
                cancel.isEnabled = true
                cancel.visibility = VISIBLE
                inputStatus.setText(R.string.ai_generating)
                insert.isEnabled = false
            }
            is AiStreamEvent.Delta -> result.append(event.text)
            is AiStreamEvent.Completed -> {
                streaming = false
                result.text = event.text
                submit.isEnabled = draft.text.isNotBlank()
                cancel.isEnabled = false
                cancel.visibility = GONE
                renderInputStatus()
                insert.isEnabled = event.text.isNotBlank()
            }
            is AiStreamEvent.Failed -> {
                streaming = false
                if (detailMode != DetailMode.CONVERSATIONS) showDetail(DetailMode.RESULT)
                else setEditing(false)
                result.setText(aiErrorMessage(event.error))
                submit.isEnabled = draft.text.isNotBlank()
                cancel.isEnabled = false
                cancel.visibility = GONE
                renderInputStatus()
                insert.isEnabled = false
            }
            AiStreamEvent.Cancelled -> {
                streaming = false
                result.text = ""
                submit.isEnabled = draft.text.isNotBlank()
                cancel.isEnabled = false
                cancel.visibility = GONE
                renderInputStatus()
                insert.isEnabled = false
            }
        }
        refreshCommands()
    }

    private fun renderInputStatus() {
        if (renaming) inputStatus.setText(R.string.ai_conversation_rename)
        else if (streaming) inputStatus.setText(R.string.ai_generating)
        else inputStatus.text = context.getString(if (editing) R.string.ai_draft_mode else R.string.ai_result_mode,
            context.getString(if (inputLanguage == InputLanguage.CHINESE) R.string.ai_input_chinese else R.string.ai_input_english))
        draft.isSelected = editing
    }

    private fun showMore() {
        val menu = androidx.appcompat.widget.PopupMenu(context, more)
        val commands = listOf(importContent, addContent, settings).filter { it !== importContent || importContent.visibility == VISIBLE }
        commands.forEachIndexed { index, button -> menu.menu.add(0, index, index, button.text) }
        menu.setOnMenuItemClickListener { commands[it.itemId].performClick(); true }
        menu.show()
    }

    fun reset() {
        renderRenaming(false)
        detailMode = DetailMode.RESULT
        updateDetailVisibility()
        contextRows.render(AiContextState())
        pageRows.render(null)
        conversationRows.status(false, false, false)
        modelMenu.render(emptyList(), null)
        renderImportedContent(false, emptyList())
        draft.text = ""
        transcript.text = ""
        languageIndex = 0
        action = AiAction.ASK
        updateActionStyles()
        renderConversations(emptyList())
        render(AiStreamEvent.Cancelled)
        setEditing(false)
    }

    override fun onDetachedFromWindow() {
        modelMenu.dismiss()
        super.onDetachedFromWindow()
    }

    private fun refreshCommands() {
        val selecting = detailMode == DetailMode.PAGE
        submit.setText(when { selecting -> R.string.ai_page_add; renaming -> R.string.ai_rename_save; else -> R.string.ai_submit })
        edit.setText(when { selecting -> R.string.ai_page_cancel; editing -> R.string.ai_read; else -> R.string.ai_edit })
        insert.visibility = if (selecting) GONE else VISIBLE
        submit.isEnabled = if (selecting) pageRows.selection.isNotEmpty() else !streaming && draft.text.isNotBlank()
    }

    private fun submit() {
        if (detailMode == DetailMode.PAGE) { onPageSelected(pageRows.selection); return }
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
            view.isCheckable = true
            view.isChecked = value == action
            val selected = value == action
            view.backgroundTintList = ColorStateList.valueOf(resolveColor(
                if (selected) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT))
            view.setTextColor(resolveColor(if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
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
        cornerRadius = dp(4)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), paddingTop, dp(8), paddingBottom)
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
