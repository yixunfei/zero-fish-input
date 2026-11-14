package dev.zeroinput.ime.ai

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R

internal class AiToolTaskView(context: Context) : LinearLayout(context) {
    var onStart: (String) -> Unit = {}
    var onStop: () -> Unit = {}
    var onPick: () -> Unit = {}
    var onClearFiles: () -> Unit = {}
    var onContext: () -> Unit = {}
    var onSkills: () -> Unit = {}
    var onConnections: () -> Unit = {}
    var onVirtualScreen: () -> Unit = {}
    var onSource: (String) -> Unit = {}
    var onNotifications: (Boolean) -> Unit = {}
    var onBackground: (Boolean) -> Unit = {}
    var onSelect: (String, Boolean) -> Unit = { _, _ -> }
    var onApprove: (String, Boolean) -> Unit = { _, _ -> }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val setup = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = true }
    private var setupExpanded = true
    private var wasRunning = false
    private val setupToggle = button(R.string.ai_tool_setup_hide) { showSetup(!setupExpanded) }
    private val selectionSummary = TextView(context)
    private val tools = LinearLayout(context).apply { orientation = VERTICAL }
    private val files = TextView(context)
    private val contextLabel = TextView(context)
    private val destination = TextView(context)
    private val status = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val timeline = TextView(context).apply { setTextIsSelectable(true) }
    private val sources = LinearLayout(context).apply { orientation = VERTICAL }
    private val approvalText = TextView(context).apply { setTextIsSelectable(true) }
    private val approvalPreview = ScrollView(context).apply { addView(approvalText); visibility = GONE }
    private val approvalActions = LinearLayout(context)
    private var approval: String? = null
    private val prompt = EditText(context).apply {
        id = R.id.ai_tool_prompt; setHint(R.string.ai_tool_prompt)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSaveEnabled = false; maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(8_192))
    }
    private val start = button(R.string.ai_tool_start) { onStart(prompt.text.toString().trim()) }.apply { id = R.id.ai_tool_start }
    private val stop = button(dev.zeroinput.ime.ui.R.string.ai_cancel) { onStop() }
    private val pick = button(R.string.ai_tool_pick) { onPick() }
    private val clearFiles = button(R.string.ai_tool_clear_files) { onClearFiles() }
    private val contextButton = button(R.string.ai_library_title) { onContext() }
    private val skills = button(R.string.ai_skill_title) { onSkills() }
    private val connections = button(R.string.ai_remote_title) { onConnections() }
    private val virtualScreen = button(R.string.ai_virtual_title) { onVirtualScreen() }
    private var updatingModes = false
    private val notificationMode = CheckBox(context).apply {
        setText(R.string.ai_run_notifications); isSaveEnabled = false
        setOnCheckedChangeListener { _, value -> if (!updatingModes) onNotifications(value) }
    }
    private val backgroundMode = CheckBox(context).apply {
        setText(R.string.ai_run_background); isSaveEnabled = false
        setOnCheckedChangeListener { _, value -> if (!updatingModes) onBackground(value) }
    }

    init {
        orientation = VERTICAL; isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        addView(scroll.apply { addView(body) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(TextView(context).apply { setText(R.string.ai_tool_title); textSize = 24f })
        listOf(destination, selectionSummary, status, setupToggle, setup).forEach(body::addView)
        setup.addView(TextView(context).apply { setText(R.string.ai_tool_notice) })
        listOf(pick, clearFiles, files, connections, tools, contextButton, skills, contextLabel, virtualScreen, notificationMode, backgroundMode).forEach(setup::addView)
        body.addView(approvalPreview, LayoutParams(LayoutParams.MATCH_PARENT, (160 * resources.displayMetrics.density).toInt()))
        listOf(approvalActions, timeline, sources).forEach(body::addView)
        approvalActions.addView(button(R.string.ai_tool_approve) { approval?.let { onApprove(it, true) } }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        approvalActions.addView(button(R.string.ai_tool_decline) { approval?.let { onApprove(it, false) } }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(prompt)
        addView(LinearLayout(context).apply {
            addView(start, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(stop, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        approvalActions.visibility = GONE
    }
    fun configure(definitions: List<AiToolDefinition>, selected: Set<String>, labels: List<String>, workspace: AiWorkspaceContext,
        running: Boolean, ready: Boolean, model: String) {
        tools.removeAllViews()
        definitions.forEach { definition -> tools.addView(CheckBox(context).apply {
            text = title(definition); isChecked = definition.id in selected; isEnabled = !running; isSaveEnabled = false
            setOnCheckedChangeListener { _, checked -> onSelect(definition.id, checked) }
        }) }
        files.text = labels.joinToString("\n")
        contextLabel.text = context.getString(R.string.ai_tool_context_count, workspace.persona?.name ?: context.getString(R.string.ai_tool_no_role),
            workspace.memories.size, workspace.skills.size, workspace.characterCount)
        destination.text = context.getString(R.string.ai_tool_destination, model)
        listOf(pick, clearFiles, contextButton, skills, connections, virtualScreen, prompt).forEach { it.isEnabled = !running }
        pick.isEnabled = !running && labels.size < 8
        start.isEnabled = !running && ready && selected.isNotEmpty(); stop.isEnabled = running
        selectionSummary.text = context.getString(R.string.ai_tool_selection_summary, selected.size, labels.size, workspace.characterCount)
        if (running && !wasRunning) showSetup(false)
        wasRunning = running
    }
    private fun showSetup(expanded: Boolean) {
        setupExpanded = expanded; setup.visibility = if (expanded) VISIBLE else GONE
        setupToggle.setText(if (expanded) R.string.ai_tool_setup_hide else R.string.ai_tool_setup_show)
    }
    fun event(value: AiToolRunEvent) {
        approval = value.approval
        approvalActions.visibility = if (value.state == AiToolRunState.APPROVAL) VISIBLE else GONE
        approvalPreview.visibility = approvalActions.visibility
        approvalText.text = if (value.call != null && value.tool != null && value.state == AiToolRunState.APPROVAL)
            context.getString(R.string.ai_tool_approval_preview, title(value.tool), AiToolJson.objectValue(value.call.arguments).toString(2),
                if (value.tool.origin == AiToolOrigin.FILE) "" else value.tool.description) else ""
        status.setText(when {
            value.failure == AiToolRunFailure.UNCERTAIN -> R.string.ai_tool_uncertain
            value.state == AiToolRunState.THINKING -> R.string.ai_tool_thinking
            value.state == AiToolRunState.APPROVAL -> R.string.ai_tool_waiting
            value.state == AiToolRunState.EXECUTING -> R.string.ai_tool_executing
            value.state == AiToolRunState.COMPLETED -> R.string.ai_tool_completed
            value.state == AiToolRunState.STOPPED -> R.string.ai_tool_stopped
            else -> R.string.ai_tool_failed
        })
        if (value.result != null && value.tool != null) {
            append(context.getString(R.string.ai_tool_result, title(value.tool), value.result.content))
            if (value.tool.origin == AiToolOrigin.SEARCH) value.result.content.lineSequence().filter(AiRemotePolicy::source).take(5).forEach { url ->
                if (sources.childCount < 25) sources.addView(MaterialButton(context).apply {
                    text = url; maxLines = 2; contentDescription = context.getString(R.string.ai_remote_open_source, url)
                    setOnClickListener { onSource(url) }
                })
            }
        }
        if (value.message.isNotBlank()) append(value.message)
        if (value.state == AiToolRunState.APPROVAL) {
            showSetup(false)
            scroll.post { scroll.scrollTo(0, approvalPreview.top); approvalPreview.scrollTo(0, 0) }
        }
    }
    private fun append(text: String) {
        timeline.text = listOf(timeline.text.toString(), text).filter(String::isNotBlank)
            .joinToString("\n\n").takeLastWholeCodePoints(100_000)
    }
    fun clear() { prompt.text?.clear(); clearHistory(); showSetup(true); wasRunning = false }
    fun error() { status.setText(R.string.ai_tool_unavailable) }
    fun hasDraft() = prompt.text?.isNotBlank() == true
    fun restoreDraft(text: String) { prompt.setText(text) }
    fun clearHistory() { timeline.text = ""; sources.removeAllViews(); approvalText.text = ""; status.text = ""; approval = null; approvalActions.visibility = GONE; approvalPreview.visibility = GONE }
    fun modes(notifications: Boolean, background: Boolean, editable: Boolean, backgroundPossible: Boolean) {
        updatingModes = true
        notificationMode.isChecked = notifications; notificationMode.isEnabled = editable
        backgroundMode.isChecked = background; backgroundMode.isEnabled = editable && notifications && backgroundPossible
        updatingModes = false
    }
    fun newRun() { clearHistory() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        prompt.maxLines = if (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom < 400 * resources.displayMetrics.density) 1 else 3
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun title(tool: AiToolDefinition) = when (tool.name) {
        AiFileTools.READ -> context.getString(R.string.ai_tool_read)
        AiFileTools.SEARCH -> context.getString(R.string.ai_tool_search_file)
        AiFileTools.SAVE -> context.getString(R.string.ai_tool_save)
        else -> tool.title
    }
    private fun button(label: Int, action: () -> Unit) = MaterialButton(context).apply {
        setText(label); maxLines = 2; minimumHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
}
