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
    private val buttons = mutableListOf<CandidateItemView>()
    private var previousSnapshot: EngineSnapshot? = null
    private var expanded = false
    private var lastStatus: InputEngineStatus? = null
    private var diagnostics: String? = null
    private var lastAnnouncedCandidate: String? = null

    init {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        orientation = if (landscape) HORIZONTAL else VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(if (landscape) 48 else 72))
        val statusRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(composition, LayoutParams(0, dp(24), 0.55f))
            addView(progress, LayoutParams(dp(18), dp(18)))
            addView(status, LayoutParams(0, dp(24), 0.45f).apply { marginEnd = dp(8) })
        }
        addView(statusRow, if (landscape) LayoutParams(0, dp(48), 1f) else LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
        addView(LinearLayout(context).apply {
            addView(tools, LayoutParams(dp(48), dp(48)))
            addView(reconvert, LayoutParams(dp(48), dp(48)))
            addView(ai, LayoutParams(dp(48), dp(48)))
            addView(undo, LayoutParams(dp(48), dp(48)))
            addView(syllable, LayoutParams(dp(48), dp(48)))
            addView(previousPage, LayoutParams(dp(48), dp(48)))
            addView(browser, LayoutParams(0, dp(48), 1f))
            addView(nextPage, LayoutParams(dp(48), dp(48)))
            addView(retry, LayoutParams(dp(48), dp(48)))
            addView(expand, LayoutParams(dp(48), dp(48)))
        }, if (landscape) LayoutParams(0, dp(48), 3f) else LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        renderStatus(InputEngineStatus.HIDDEN)
        undo.visibility = GONE
        syllable.visibility = GONE
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
        undo.visibility = if (snapshot.canUndoSelection) VISIBLE else GONE
        syllable.visibility = if (snapshot.canSelectSyllable && !snapshot.canUndoSelection) VISIBLE else GONE
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
        expand.visibility = if (snapshot.candidates.isEmpty() || associations) View.INVISIBLE else View.VISIBLE
        previousPage.visibility = if (snapshot.hasPreviousPage && !associations) View.VISIBLE else View.GONE
        nextPage.visibility = if (snapshot.hasNextPage && !associations) View.VISIBLE else View.GONE
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
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        expand.setImageResource(if (value) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
        expand.contentDescription = context.getString(if (value) R.string.collapse_candidates else R.string.expand_candidates)
    }

    fun renderAiEntry(available: Boolean, visible: Boolean) {
        ai.visibility = if (visible) VISIBLE else GONE
        ai.renderAiEntryAvailability(available)
    }

    fun renderReconversion(available: Boolean) {
        reconvert.visibility = if (available) VISIBLE else GONE
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
        retry.visibility = if (value == InputEngineStatus.FAILED) View.VISIBLE else View.GONE
        updateStatusVisibility()
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

    private fun updateStatusVisibility() {
        status.visibility = if (diagnostics == null && lastStatus == InputEngineStatus.READY && previousSnapshot?.isComposing == true)
            View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
