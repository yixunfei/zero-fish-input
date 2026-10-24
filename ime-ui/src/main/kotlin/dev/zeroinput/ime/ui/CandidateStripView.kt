package dev.zeroinput.ime.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection

class CandidateStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onCandidateSelected: (Int) -> Unit = {}
    var onExpandRequested: () -> Unit = {}
    var onRetryRequested: () -> Unit = {}
    var onToolsRequested: () -> Unit = {}
    var onAiRequested: () -> Unit = {}
    var onReconvertRequested: () -> Unit = {}
    var onPageChanged: (PageDirection) -> Unit = {}
    var onCompositionSelection: (dev.zeroinput.engine.api.CompositionSelection, String) -> Unit = { _, _ -> }
    private val composition = CompositionEditorView(context).apply {
        onSelection = { range, input -> onCompositionSelection(range, input) }
    }
    private val status = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
        setHorizontallyScrolling(true)
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val candidates = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val scroll = CandidateScrollView(context).apply {
        canLoad = { if (it == PageDirection.NEXT) previousSnapshot?.hasNextPage == true else previousSnapshot?.hasPreviousPage == true }
        onNearEdge = ::requestMore
        onBrowseStarted = { attempted = null }
        addView(candidates)
    }
    private val expand = panelIconButton(context, R.drawable.ic_panel_expand, R.string.expand_candidates) { onExpandRequested() }
        .apply { rotation = 180f }
    private var attempted: Pair<EngineSnapshot, PageDirection>? = null
    private var loadTask: Runnable? = null
    private var fillAllowed = true
    private var restoringScroll = false
    private val tools = panelIconButton(context, R.drawable.ic_keyboard_tools, R.string.keyboard_tools) { onToolsRequested() }
    private val reconvert = panelIconButton(context, android.R.drawable.ic_menu_revert, R.string.reconvert_last_word) {
        onReconvertRequested()
    }.apply { visibility = GONE }
    private val ai = aiEntryButton(context) { onAiRequested() }
        .apply { visibility = GONE }
    private val retry = panelIconButton(context, android.R.drawable.ic_popup_sync, R.string.retry_engine) { onRetryRequested() }
    private val buttons = mutableListOf<CandidateItemView>()
    private var previousSnapshot: EngineSnapshot? = null
    private var expanded = false
    private var lastStatus: InputEngineStatus? = null
    private var lastAnnouncedCandidate: String? = null
    private var actionDensity = CandidateActionDensity.FULL
    private var candidateInline = false
    private var aiEntryVisible = false
    private var reconversionAvailable = false
    private var pendingAnchor: Pair<String, Int>? = null
    private val statusRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(composition, LayoutParams(0, dp(48), 0.55f))
        addView(progress, LayoutParams(dp(18), dp(18)))
        addView(status, LayoutParams(0, dp(48), 0.45f).apply { marginEnd = dp(8) })
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(tools, LayoutParams(dp(48), dp(48)))
        addView(reconvert, LayoutParams(dp(48), dp(48)))
        addView(ai, LayoutParams(dp(48), dp(48)))
        addView(scroll, LayoutParams(0, dp(48), 1f))
        addView(retry, LayoutParams(dp(48), dp(48)))
        addView(expand, LayoutParams(dp(48), dp(48)))
    }

    init {
        orientation = VERTICAL
        addView(statusRow)
        addView(actionRow)
        updateViewportLayout()
        renderStatus(InputEngineStatus.HIDDEN)
    }

    internal fun applyViewport(plan: KeyboardViewportPlan) {
        if (candidateInline == plan.candidateInline && actionDensity == plan.candidateActions) return
        candidateInline = plan.candidateInline
        actionDensity = plan.candidateActions
        buttons.forEach { it.setCompactLayout(actionDensity != CandidateActionDensity.FULL) }
        updateViewportLayout()
        updateActionVisibility()
    }

    fun render(snapshot: EngineSnapshot) {
        if (snapshot == previousSnapshot) return
        val oldCandidates = previousSnapshot?.candidates.orEmpty()
        loadTask?.let(::removeCallbacks)
        loadTask = null
        val changedInput = snapshot.rawInput != previousSnapshot?.rawInput ||
            snapshot.composition != previousSnapshot?.composition
        val anchorIndex = buttons.indexOfFirst { it.isVisible && it.right > scroll.scrollX }
        fillAllowed = changedInput || oldCandidates.firstOrNull()?.id?.let { id -> snapshot.candidates.any { it.id == id } } != false
        pendingAnchor = if (!changedInput)
            pendingAnchor ?: oldCandidates.getOrNull(anchorIndex)?.let { it.id to (buttons[anchorIndex].left - scroll.scrollX) }
            else null
        previousSnapshot = snapshot
        if (snapshot.candidates.isEmpty()) lastAnnouncedCandidate = null
        val associations = snapshot.candidates.firstOrNull()?.kind == dev.zeroinput.engine.api.CandidateKind.NEXT_WORD
        composition.render(snapshot, if (associations) context.getString(R.string.word_associations)
            else snapshot.composition.ifEmpty { snapshot.rawInput })
        while (buttons.size < snapshot.candidates.size) {
            buttons += CandidateItemView(context).also { button ->
                button.setCompactLayout(actionDensity != CandidateActionDensity.FULL)
                button.onSelected = { index -> onCandidateSelected(index) }
                candidates.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            }
        }
        buttons.forEachIndexed { index, button ->
            val candidate = snapshot.candidates.getOrNull(index)
            button.visibility = if (candidate == null) View.GONE else View.VISIBLE
            if (candidate != null) button.bind(candidate, index, index == snapshot.highlightedIndex) else button.clear()
        }
        if (changedInput) {
            restoringScroll = true
            try { scroll.resetToStart() } finally { restoringScroll = false }
        }
        if (changedInput && snapshot.candidates.isNotEmpty()) {
            val announcement = snapshot.candidates.first().text
            if (announcement != lastAnnouncedCandidate) {
                lastAnnouncedCandidate = announcement
                announceForAccessibility(announcement)
            }
        }
        updateStatusVisibility()
        updateActionVisibility()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Keep even a sentence-sized first result from consuming the entire strip.
        val limit = (scroll.measuredWidth * 0.65f).toInt().coerceAtLeast(dp(48))
        var changed = false
        buttons.forEach { if (it.maxWidth != limit) { it.maxWidth = limit; changed = true } }
        if (changed) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pendingAnchor?.let { (id, offset) ->
            val index = previousSnapshot?.candidates?.indexOfFirst { it.id == id } ?: -1
            buttons.getOrNull(index)?.let { restoreScroll(it.left - offset) }
        }
        pendingAnchor = null
        if (fillAllowed) scroll.checkEdge(PageDirection.NEXT)
    }

    private fun requestMore(direction: PageDirection) {
        val snapshot = previousSnapshot ?: return
        if (expanded || restoringScroll || loadTask != null || attempted == (snapshot to direction)) return
        attempted = snapshot to direction
        val task = Runnable {
            loadTask = null
            if (!expanded && isShown && previousSnapshot == snapshot) onPageChanged(direction)
        }
        loadTask = task
        post(task)
    }

    private fun restoreScroll(position: Int) {
        restoringScroll = true
        try { scroll.scrollTo(position, 0) } finally { restoringScroll = false }
    }

    override fun onDetachedFromWindow() {
        loadTask?.let(::removeCallbacks)
        loadTask = null
        attempted = null
        super.onDetachedFromWindow()
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (value) {
            loadTask?.let(::removeCallbacks)
            loadTask = null
        }
        expand.rotation = if (value) 0f else 180f
        expand.contentDescription = context.getString(if (value) R.string.collapse_candidates else R.string.expand_candidates)
    }

    fun renderAiEntry(available: Boolean, visible: Boolean) {
        aiEntryVisible = visible
        ai.renderAiEntryAvailability(available)
        updateActionVisibility()
    }

    fun renderReconversion(available: Boolean) {
        reconversionAvailable = available
        updateActionVisibility()
    }

    fun renderStatus(value: InputEngineStatus) {
        if (value == lastStatus) return
        lastStatus = value
        val label = when (value) {
            InputEngineStatus.PREPARING -> R.string.engine_preparing
            InputEngineStatus.PENDING_CONFIGURATION -> R.string.engine_pending_configuration
            InputEngineStatus.FAILED -> R.string.engine_failed
            InputEngineStatus.READY -> R.string.engine_ready
            InputEngineStatus.HIDDEN -> null
        }
        status.text = label?.let(context::getString).orEmpty()
        progress.visibility = if (value == InputEngineStatus.PREPARING) View.VISIBLE else View.GONE
        updateStatusVisibility()
        updateActionVisibility()
    }

    private fun updateViewportLayout() {
        orientation = if (candidateInline) HORIZONTAL else VERTICAL
        statusRow.layoutParams = if (candidateInline) LayoutParams(0, dp(48), 1f)
            else LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
        actionRow.layoutParams = if (candidateInline) LayoutParams(0, dp(48), 3f)
            else LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun updateActionVisibility() {
        val snapshot = previousSnapshot
        val associations = snapshot?.candidates?.firstOrNull()?.kind == dev.zeroinput.engine.api.CandidateKind.NEXT_WORD
        val canExpand = snapshot?.candidates?.isNotEmpty() == true && !associations
        val canRetry = lastStatus == InputEngineStatus.FAILED
        val compact = actionDensity != CandidateActionDensity.FULL
        val hasCandidates = snapshot?.candidates?.isNotEmpty() == true
        tools.visibility = VISIBLE
        reconvert.visibility = if (!hasCandidates && !compact && reconversionAvailable) VISIBLE else GONE
        ai.visibility = if (!hasCandidates && aiEntryVisible) VISIBLE else GONE
        retry.visibility = if (canRetry) VISIBLE else GONE
        expand.visibility = if (canExpand) VISIBLE else GONE
    }

    private fun updateStatusVisibility() {
        status.visibility = if (lastStatus == InputEngineStatus.READY && previousSnapshot?.isComposing == true)
            View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
