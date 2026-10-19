package dev.zeroinput.ime.ui

import android.content.Context
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.os.Bundle
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CandidateKind
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection

/** A continuous viewport over the core's bounded candidate window. */
internal class ExpandedCandidatesView(context: Context) : LinearLayout(context) {
    var onCandidateSelected: (Int) -> Unit = {}
    var onPageChanged: (PageDirection) -> Unit = {}
    private var appearance = ExpandedCandidateAppearance()
    private val separators = CandidateSeparators()
    private val rowContinuity = CandidateRowContinuity()
    private var candidateSpans: List<Int> = emptyList()
    private val textPaint = TextPaint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
    }
    private var resetPending = false
    private var pendingAnchor: Pair<String, Int>? = null
    private val layout = object : GridLayoutManager(context, 1) {
        override fun onLayoutCompleted(state: RecyclerView.State?) {
            super.onLayoutCompleted(state)
            resetPending = false
            pendingAnchor = null
        }
    }.apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = candidateSpans.getOrNull(position) ?: spanCount
        }
    }
    private val adapter = CandidateAdapter()
    private var lastSnapshot = EngineSnapshot.Empty
    private var revision = 0L
    private var pending: PageDirection? = null
    private var dispatchTask: Runnable? = null
    private var attempted: Pair<EngineSnapshot, PageDirection>? = null
    private var browseDirection = PageDirection.NEXT
    private val prefetch = Runnable { prefetch(browseDirection) }
    private val scroll = object : RecyclerView(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) attempted = null
            return super.dispatchTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val consumed = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                prefetch(browseDirection)
            }
            return consumed
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (super.performAccessibilityAction(action, arguments)) return true
            val direction = when (action) {
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> PageDirection.NEXT
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> PageDirection.PREVIOUS
                else -> return false
            }
            val available = if (direction == PageDirection.NEXT) lastSnapshot.hasNextPage else lastSnapshot.hasPreviousPage
            if (!available) return false
            attempted = null
            browseDirection = direction
            requestPage(direction)
            return true
        }
    }.apply {
        layoutManager = layout
        adapter = this@ExpandedCandidatesView.adapter
        setHasFixedSize(true)
        itemAnimator = null
        addItemDecoration(separators)
        isSaveEnabled = false
        setItemViewCacheSize(6)
        setAccessibilityDelegateCompat(object : RecyclerViewAccessibilityDelegate(this) {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (lastSnapshot.hasNextPage) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                if (lastSnapshot.hasPreviousPage) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            }
        })
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) {
                    browseDirection = if (dy > 0) PageDirection.NEXT else PageDirection.PREVIOUS
                    prefetch(browseDirection)
                }
            }
        })
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun applyAppearance(value: ExpandedCandidateAppearance) {
        val updated = value.sanitized()
        if (appearance == updated) return
        val position = layout.findFirstVisibleItemPosition()
        val offset = layout.findViewByPosition(position)?.top ?: 0
        scroll.stopScroll()
        appearance = updated
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            updated.textSize.toFloat(), resources.displayMetrics)
        separators.enabled = updated.separators
        updatePacking(preserve = false)
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        if (position >= 0) layout.scrollToPositionWithOffset(position, offset)
        scroll.invalidateItemDecorations()
        schedulePrefetch()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updatePacking(width)
        schedulePrefetch()
    }

    fun render(snapshot: EngineSnapshot) {
        // An unchanged personalization/status delivery must not cancel an edge
        // request already posted by the current drag or fling.
        if (snapshot == lastSnapshot) return
        dispatchTask?.let(::removeCallbacks)
        dispatchTask = null
        val request = pending
        pending = null
        val position = layout.findFirstVisibleItemPosition()
        val old = adapter.items
        val sameInput = snapshot.rawInput == lastSnapshot.rawInput && snapshot.composition == lastSnapshot.composition
        pendingAnchor = if (sameInput && !resetPending) pendingAnchor ?: old.getOrNull(position)?.let {
            it.id to (layout.findViewByPosition(position)?.top ?: 0)
        } else null
        val removedHead = old.firstOrNull()?.id?.let { id -> snapshot.candidates.none { it.id == id } } == true
        val removedTail = old.lastOrNull()?.id?.let { id -> snapshot.candidates.none { it.id == id } } == true
        lastSnapshot = snapshot
        revision++
        adapter.replace(snapshot.candidates, snapshot.highlightedIndex, preserveRows = sameInput)
        val preserved = pendingAnchor?.let { anchor -> snapshot.candidates.indexOfFirst { it.id == anchor.first } } ?: -1
        when {
            !sameInput || resetPending -> {
                browseDirection = PageDirection.NEXT
                scroll.stopScroll()
                // Until layout completes, attached children still describe the old input.
                // A second delivery must not restore an anchor captured from those children.
                resetPending = true
                layout.scrollToPositionWithOffset(0, 0)
            }
            preserved >= 0 && preserved != position ->
                layout.scrollToPositionWithOffset(preserved, checkNotNull(pendingAnchor).second)
        }
        if (sameInput && request != null) browseDirection = request
        // Both directions continue during drag/fling. Idle filling stops at
        // eviction so a viewport larger than the bounded window cannot drain it.
        val evicted = if (browseDirection == PageDirection.NEXT) removedHead else removedTail
        if (!evicted || scroll.scrollState != RecyclerView.SCROLL_STATE_IDLE) schedulePrefetch()
    }

    private fun schedulePrefetch() {
        removeCallbacks(prefetch)
        postOnAnimation(prefetch)
    }

    private fun updatePacking(width: Int = scroll.width, preserve: Boolean = true) {
        val available = width.coerceAtLeast(1)
        candidateSpans = rowContinuity.pack(adapter.items.map { it.id }, adapter.items.map { candidate ->
            val label = if (candidate.kind == CandidateKind.RELATED_READING)
                context.getString(R.string.related_candidate, candidate.text) else candidate.text
            if (appearance.layout == ExpandedCandidateLayout.LIST) available else
                (kotlin.math.ceil(textPaint.measureText(label)).toInt() + dp(appearance.density.horizontalPadding * 2))
                    .coerceAtLeast(dp(appearance.density.minimumWidth))
        }, available, preserve)
        layout.spanCount = available
        layout.spanSizeLookup.invalidateSpanIndexCache()
        layout.spanSizeLookup.invalidateSpanGroupIndexCache()
        scroll.requestLayout()
    }

    private fun prefetch(direction: PageDirection) {
        if (!isShown || scroll.height == 0 || adapter.itemCount == 0) return
        if (scroll.isLayoutRequested) { schedulePrefetch(); return }
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return
        fun row(position: Int) = layout.spanSizeLookup.getSpanGroupIndex(position, layout.spanCount)
        val nearEdge = if (direction == PageDirection.NEXT)
            row(adapter.itemCount - 1) - row(last) <= 2
        else row(first) <= 2
        if (nearEdge) requestPage(direction)
    }

    private fun requestPage(direction: PageDirection) {
        val available = if (direction == PageDirection.NEXT) lastSnapshot.hasNextPage else lastSnapshot.hasPreviousPage
        if (!available || pending != null || attempted == (lastSnapshot to direction)) return
        dispatchTask?.let(::removeCallbacks)
        pending = direction
        attempted = lastSnapshot to direction
        val expected = revision
        // Never mutate the adapter from RecyclerView's layout/scroll callback.
        val dispatch = Runnable {
            dispatchTask = null
            if (revision == expected && isShown) onPageChanged(direction)
            if (revision == expected) pending = null
        }
        dispatchTask = dispatch
        post(dispatch)
    }

    fun clear() {
        removeCallbacks(prefetch)
        dispatchTask?.let(::removeCallbacks)
        dispatchTask = null
        scroll.stopScroll()
        revision++
        pending = null
        pendingAnchor = null
        attempted = null
        render(EngineSnapshot.Empty)
        for (index in 0 until scroll.childCount) (scroll.getChildAt(index) as? CandidateItemView)?.clear()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(prefetch)
        dispatchTask?.let(::removeCallbacks)
        dispatchTask = null
        pending = null
        pendingAnchor = null
        attempted = null
        revision++
        super.onDetachedFromWindow()
    }

    private inner class CandidateAdapter : RecyclerView.Adapter<CandidateHolder>() {
        var items: List<Candidate> = emptyList()
            private set
        private var highlighted = 0

        fun replace(values: List<Candidate>, selected: Int, preserveRows: Boolean) {
            if (items == values && highlighted == selected) return
            val old = items
            val oldHighlight = highlighted
            // The core bounds both lists. Page insertion/eviction needs no moves.
            val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = values.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a].id == values[b].id
                override fun areContentsTheSame(a: Int, b: Int) =
                    old[a] == values[b] && a == b && (a == oldHighlight) == (b == selected)
            }, false)
            items = values
            highlighted = selected
            if (old != values) updatePacking(preserve = preserveRows)
            changes.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateHolder =
            CandidateHolder(CandidateItemView(parent.context).apply {
                setSingleLine(false)
                maxLines = Int.MAX_VALUE
                ellipsize = null
                applyExpandedAppearance(appearance)
                layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                onSelected = { onCandidateSelected(it) }
            })

        override fun onBindViewHolder(holder: CandidateHolder, position: Int) {
            holder.view.applyExpandedAppearance(appearance)
            holder.view.bind(items[position], position, position == highlighted)
        }

        override fun onViewRecycled(holder: CandidateHolder) { holder.view.clear() }
    }

    private class CandidateHolder(val view: CandidateItemView) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
