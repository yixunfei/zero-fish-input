package dev.zeroinput.ime.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection

class CandidateStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onCandidateSelected: (Int) -> Unit = {}
    var onExpandRequested: () -> Unit = {}
    var onRetryRequested: () -> Unit = {}
    var onToolsRequested: () -> Unit = {}
    var onAiRequested: () -> Unit = {}
    var onReconvertRequested: () -> Unit = {}
    var onUndoSelectionRequested: () -> Unit = {}
    var onSyllableRequested: () -> Unit = {}
    var onPageChanged: (PageDirection) -> Unit = {}
    private val composition = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(dp(8), 0, dp(8), 0)
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
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(candidates)
    }
    private val expand = panelIconButton(context, android.R.drawable.arrow_down_float, R.string.expand_candidates) { onExpandRequested() }
    private var requestedPage: PageDirection? = null
    private val browser = HorizontalSwipeFrameLayout(context).apply {
        canSwipe = { direction ->
            val snapshot = previousSnapshot
            if (direction == PageDirection.NEXT) snapshot?.hasNextPage == true && !scroll.canScrollHorizontally(1)
            else snapshot?.hasPreviousPage == true && !scroll.canScrollHorizontally(-1)
        }
        onSwipe = { direction -> requestedPage = direction; onPageChanged(direction); requestedPage = null }
        addView(scroll)
    }
    private val previousPage = panelIconButton(context, android.R.drawable.ic_media_previous, R.string.previous_candidates) {
        if (previousSnapshot?.hasPreviousPage == true) onPageChanged(PageDirection.PREVIOUS)
    }
    private val nextPage = panelIconButton(context, android.R.drawable.ic_media_next, R.string.next_candidates) {
        if (previousSnapshot?.hasNextPage == true) onPageChanged(PageDirection.NEXT)
    }
    private val tools = panelIconButton(context, R.drawable.ic_keyboard_tools, R.string.keyboard_tools) { onToolsRequested() }
    private val reconvert = panelIconButton(context, android.R.drawable.ic_menu_revert, R.string.reconvert_last_word) {
        onReconvertRequested()
    }.apply { visibility = GONE }
    private val ai = aiEntryButton(context) { onAiRequested() }
        .apply { visibility = GONE }
    private val undo = panelIconButton(context, android.R.drawable.ic_menu_revert, R.string.undo_segment) { onUndoSelectionRequested() }
    private val syllable = panelIconButton(context, android.R.drawable.ic_menu_edit, R.string.select_single_syllable) { onSyllableRequested() }
    private val retry = panelIconButton(context, android.R.drawable.ic_popup_sync, R.string.retry_engine) { onRetryRequested() }
    private val overflow = panelIconButton(context, android.R.drawable.ic_menu_more, R.string.candidate_more_actions) {
        showOverflowActions()
    }
    private val buttons = mutableListOf<CandidateItemView>()
    private var previousSnapshot: EngineSnapshot? = null
    private var expanded = false
    private var lastStatus: InputEngineStatus? = null
    private var diagnostics: String? = null
    private var lastAnnouncedCandidate: String? = null
    private var actionDensity = CandidateActionDensity.FULL
    private var candidateInline = false
    private var aiEntryVisible = false
    private var reconversionAvailable = false
    private val statusRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(composition, LayoutParams(0, dp(24), 0.55f))
        addView(progress, LayoutParams(dp(18), dp(18)))
        addView(status, LayoutParams(0, dp(24), 0.45f).apply { marginEnd = dp(8) })
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(tools, LayoutParams(dp(48), dp(48)))
        addView(reconvert, LayoutParams(dp(48), dp(48)))
        addView(ai, LayoutParams(dp(48), dp(48)))
        addView(undo, LayoutParams(dp(48), dp(48)))
        addView(syllable, LayoutParams(dp(48), dp(48)))
        addView(previousPage, LayoutParams(dp(48), dp(48)))
        addView(browser, LayoutParams(0, dp(48), 1f))
        addView(nextPage, LayoutParams(dp(48), dp(48)))
        addView(retry, LayoutParams(dp(48), dp(48)))
        addView(overflow, LayoutParams(dp(48), dp(48)))
        addView(expand, LayoutParams(dp(48), dp(48)))
    }

    init {
        orientation = VERTICAL
        addView(statusRow)
        addView(actionRow)
        updateViewportLayout()
        renderStatus(InputEngineStatus.HIDDEN)
        undo.visibility = GONE
        syllable.visibility = GONE
    }

    internal fun applyViewport(plan: KeyboardViewportPlan) {
        if (candidateInline == plan.candidateInline && actionDensity == plan.candidateActions) return
        candidateInline = plan.candidateInline
        actionDensity = plan.candidateActions
        updateViewportLayout()
        updateActionVisibility()
    }

    fun render(snapshot: EngineSnapshot) {
        if (snapshot == previousSnapshot) return
        browser.cancelSwipe()
        val oldTexts = previousSnapshot?.candidates.orEmpty().map { it.text }.toSet()
        val pageTarget = if (requestedPage == PageDirection.NEXT)
            snapshot.candidates.indexOfFirst { it.text !in oldTexts }.coerceAtLeast(0) else 0
        val changedInput = snapshot.rawInput != previousSnapshot?.rawInput ||
            snapshot.candidates != previousSnapshot?.candidates
        previousSnapshot = snapshot
        if (snapshot.candidates.isEmpty()) lastAnnouncedCandidate = null
        val associations = snapshot.candidates.firstOrNull()?.kind == dev.zeroinput.engine.api.CandidateKind.NEXT_WORD
        composition.text = if (associations) context.getString(R.string.word_associations)
            else snapshot.composition.ifEmpty { snapshot.rawInput }
        while (buttons.size < snapshot.candidates.size) {
            buttons += CandidateItemView(context).also { button ->
                button.onSelected = { index -> onCandidateSelected(index) }
                candidates.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            }
        }
        buttons.forEachIndexed { index, button ->
            val candidate = snapshot.candidates.getOrNull(index)
            button.visibility = if (candidate == null) View.GONE else View.VISIBLE
            if (candidate != null) button.bind(candidate, index, index == snapshot.highlightedIndex) else button.clear()
        }
        previousPage.isEnabled = snapshot.hasPreviousPage && !associations
        nextPage.isEnabled = snapshot.hasNextPage && !associations
        if (changedInput) scroll.scrollTo(0, 0)
        if (changedInput && snapshot.candidates.isNotEmpty()) {
            val announcement = snapshot.candidates.first().text
            if (announcement != lastAnnouncedCandidate) {
                lastAnnouncedCandidate = announcement
                announceForAccessibility(announcement)
            }
        }
        if (pageTarget > 0) scroll.post {
            if (previousSnapshot == snapshot) buttons.getOrNull(pageTarget)?.let { scroll.scrollTo(it.left, 0) }
        }
        updateStatusVisibility()
        updateActionVisibility()
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        expand.setImageResource(if (value) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
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
        status.text = diagnostics ?: label?.let(context::getString).orEmpty()
        progress.visibility = if (value == InputEngineStatus.PREPARING) View.VISIBLE else View.GONE
        updateStatusVisibility()
        updateActionVisibility()
    }

    /** Shows the Debug-only editor metadata diagnostic while preserving the composition text. */
    fun renderDiagnostics(value: String?) {
        if (diagnostics == value) return
        diagnostics = value
        val label = when (lastStatus) {
            InputEngineStatus.PREPARING -> R.string.engine_preparing
            InputEngineStatus.PENDING_CONFIGURATION -> R.string.engine_pending_configuration
            InputEngineStatus.FAILED -> R.string.engine_failed
            InputEngineStatus.READY -> R.string.engine_ready
            InputEngineStatus.HIDDEN, null -> null
        }
        status.text = value ?: label?.let(context::getString).orEmpty()
        updateStatusVisibility()
    }

    private fun updateViewportLayout() {
        orientation = if (candidateInline) HORIZONTAL else VERTICAL
        statusRow.layoutParams = if (candidateInline) LayoutParams(0, dp(48), 1f)
            else LayoutParams(LayoutParams.MATCH_PARENT, dp(24))
        actionRow.layoutParams = if (candidateInline) LayoutParams(0, dp(48), 3f)
            else LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun updateActionVisibility() {
        val snapshot = previousSnapshot
        val associations = snapshot?.candidates?.firstOrNull()?.kind == dev.zeroinput.engine.api.CandidateKind.NEXT_WORD
        val canExpand = snapshot?.candidates?.isNotEmpty() == true && !associations
        val canUndo = snapshot?.canUndoSelection == true
        val canSelectSyllable = snapshot?.canSelectSyllable == true && !canUndo
        val canPrevious = snapshot?.hasPreviousPage == true && !associations
        val canNext = snapshot?.hasNextPage == true && !associations
        val canRetry = lastStatus == InputEngineStatus.FAILED
        val compact = actionDensity != CandidateActionDensity.FULL
        val minimal = actionDensity == CandidateActionDensity.MINIMAL
        tools.visibility = if (minimal) GONE else VISIBLE
        reconvert.visibility = if (!compact && reconversionAvailable) VISIBLE else GONE
        ai.visibility = if (!compact && aiEntryVisible) VISIBLE else GONE
        undo.visibility = if (!compact && canUndo) VISIBLE else GONE
        syllable.visibility = if (!compact && canSelectSyllable) VISIBLE else GONE
        previousPage.visibility = if (!compact && canPrevious) VISIBLE else GONE
        nextPage.visibility = if (!compact && canNext) VISIBLE else GONE
        retry.visibility = if (!compact && canRetry) VISIBLE else GONE
        expand.visibility = if (!minimal && canExpand) VISIBLE else GONE
        overflow.visibility = if (compact && (minimal || reconversionAvailable || aiEntryVisible || canUndo ||
                canSelectSyllable || canPrevious || canNext || canRetry || canExpand)) VISIBLE else GONE
    }

    private fun showOverflowActions() {
        val snapshot = previousSnapshot
        val associations = snapshot?.candidates?.firstOrNull()?.kind == dev.zeroinput.engine.api.CandidateKind.NEXT_WORD
        PopupMenu(context, overflow).apply {
            if (actionDensity == CandidateActionDensity.MINIMAL) menu.add(0, ACTION_TOOLS, 0, R.string.keyboard_tools)
            if (reconversionAvailable) menu.add(0, ACTION_RECONVERT, 1, R.string.reconvert_last_word)
            if (aiEntryVisible) menu.add(0, ACTION_AI, 2, R.string.ai_open)
            if (snapshot?.canUndoSelection == true) menu.add(0, ACTION_UNDO, 3, R.string.undo_segment)
            if (snapshot?.canSelectSyllable == true && snapshot.canUndoSelection.not()) {
                menu.add(0, ACTION_SYLLABLE, 4, R.string.select_single_syllable)
            }
            if (snapshot?.hasPreviousPage == true && !associations) menu.add(0, ACTION_PREVIOUS, 5, R.string.previous_candidates)
            if (snapshot?.hasNextPage == true && !associations) menu.add(0, ACTION_NEXT, 6, R.string.next_candidates)
            if (lastStatus == InputEngineStatus.FAILED) menu.add(0, ACTION_RETRY, 7, R.string.retry_engine)
            if (snapshot?.candidates?.isNotEmpty() == true && !associations) {
                menu.add(0, ACTION_EXPAND, 8, if (expanded) R.string.collapse_candidates else R.string.expand_candidates)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ACTION_TOOLS -> onToolsRequested()
                    ACTION_RECONVERT -> onReconvertRequested()
                    ACTION_AI -> onAiRequested()
                    ACTION_UNDO -> onUndoSelectionRequested()
                    ACTION_SYLLABLE -> onSyllableRequested()
                    ACTION_PREVIOUS -> onPageChanged(PageDirection.PREVIOUS)
                    ACTION_NEXT -> onPageChanged(PageDirection.NEXT)
                    ACTION_RETRY -> onRetryRequested()
                    ACTION_EXPAND -> onExpandRequested()
                }
                true
            }
            show()
        }
    }

    private fun updateStatusVisibility() {
        status.visibility = if (diagnostics == null && lastStatus == InputEngineStatus.READY && previousSnapshot?.isComposing == true)
            View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ACTION_TOOLS = 1
        const val ACTION_RECONVERT = 2
        const val ACTION_AI = 3
        const val ACTION_UNDO = 4
        const val ACTION_SYLLABLE = 5
        const val ACTION_PREVIOUS = 6
        const val ACTION_NEXT = 7
        const val ACTION_RETRY = 8
        const val ACTION_EXPAND = 9
    }
}
