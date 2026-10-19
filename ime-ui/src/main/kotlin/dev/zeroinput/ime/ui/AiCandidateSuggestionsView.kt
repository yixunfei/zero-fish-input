package dev.zeroinput.ime.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.view.isVisible
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection

/** Candidate controls dedicated to the independent AI draft. */
internal class AiCandidateSuggestionsView(context: Context) : LinearLayout(context) {
    var onCandidateSelected: (Int, String) -> Unit = { _, _ -> }
    var onPageChanged: (PageDirection) -> Unit = {}
    var onExpandRequested: () -> Unit = {}
    private var snapshot = EngineSnapshot.Empty
    private var attempted: Pair<EngineSnapshot, PageDirection>? = null
    private var pendingPage: Runnable? = null
    private var fillAllowed = true
    private var pendingAnchor: Pair<String, Int>? = null
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val buttons = mutableListOf<CandidateItemView>()
    private val scroll = CandidateScrollView(context).apply {
        canLoad = { direction ->
            if (direction == PageDirection.NEXT) snapshot.hasNextPage else snapshot.hasPreviousPage
        }
        onNearEdge = ::requestPage
        onBrowseStarted = { attempted = null }
        addView(row)
    }
    private val expand = panelIconButton(context, R.drawable.ic_panel_expand, R.string.expand_candidates) {
        onExpandRequested()
    }.apply { rotation = 180f }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(scroll, LayoutParams(0, dp(48), 1f))
        addView(expand, LayoutParams(dp(48), dp(48)))
        visibility = GONE
    }

    fun render(value: EngineSnapshot) {
        if (snapshot == value) return
        val oldCandidates = snapshot.candidates
        val changedInput = value.rawInput != snapshot.rawInput || value.composition != snapshot.composition
        val anchorIndex = buttons.indexOfFirst { it.isVisible && it.right > scroll.scrollX }
        fillAllowed = changedInput || oldCandidates.firstOrNull()?.id?.let { id -> value.candidates.any { it.id == id } } != false
        pendingAnchor = if (changedInput) null else pendingAnchor ?: oldCandidates.getOrNull(anchorIndex)?.let {
            it.id to (buttons[anchorIndex].left - scroll.scrollX)
        }
        pendingPage?.let(::removeCallbacks)
        pendingPage = null
        attempted = null
        snapshot = value
        while (buttons.size < value.candidates.size) {
            buttons += CandidateItemView(context).also { button ->
                button.onSelected = ::dispatchSelection
                row.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            }
        }
        buttons.forEachIndexed { index, button ->
            val candidate = value.candidates.getOrNull(index)
            button.visibility = if (candidate == null) GONE else VISIBLE
            if (candidate == null) button.clear()
            else button.bind(candidate, index, index == value.highlightedIndex)
        }
        if (changedInput) scroll.resetToStart()
        visibility = if (value.candidates.isEmpty()) GONE else VISIBLE
        expand.visibility = if (value.candidates.isNotEmpty()) VISIBLE else GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val limit = (scroll.measuredWidth * 0.65f).toInt().coerceAtLeast(dp(48))
        var changed = false
        buttons.forEach { if (it.maxWidth != limit) { it.maxWidth = limit; changed = true } }
        if (changed) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pendingAnchor?.let { (id, offset) ->
            val index = snapshot.candidates.indexOfFirst { it.id == id }
            buttons.getOrNull(index)?.let { scroll.scrollTo(it.left - offset, 0) }
        }
        pendingAnchor = null
        if (fillAllowed) scroll.checkEdge(PageDirection.NEXT)
    }

    private fun dispatchSelection(index: Int) {
        val candidate = snapshot.candidates.getOrNull(index) ?: return
        onCandidateSelected(index, candidate.id)
    }

    private fun requestPage(direction: PageDirection) {
        val available = if (direction == PageDirection.NEXT) snapshot.hasNextPage else snapshot.hasPreviousPage
        if (!available || attempted == (snapshot to direction) || pendingPage != null) return
        attempted = snapshot to direction
        val expected = snapshot
        val task = Runnable {
            pendingPage = null
            if (snapshot == expected && isShown) onPageChanged(direction)
        }
        pendingPage = task
        post(task)
    }

    fun setExpanded(value: Boolean) {
        expand.rotation = if (value) 0f else 180f
        expand.contentDescription = context.getString(
            if (value) R.string.collapse_candidates else R.string.expand_candidates,
        )
    }

    fun clear() {
        pendingPage?.let(::removeCallbacks)
        pendingPage = null
        attempted = null
        pendingAnchor = null
        render(EngineSnapshot.Empty)
    }

    override fun onDetachedFromWindow() {
        pendingPage?.let(::removeCallbacks)
        pendingPage = null
        attempted = null
        pendingAnchor = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
