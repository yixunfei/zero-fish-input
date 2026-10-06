package dev.zeroinput.ime.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection

/** A continuous viewport over the core's bounded candidate window. */
internal class ExpandedCandidatesView(context: Context) : LinearLayout(context) {
    var onCandidateSelected: (Int) -> Unit = {}
    var onPageChanged: (PageDirection) -> Unit = {}
    private val layout = GridLayoutManager(context, 3)
    private val adapter = CandidateAdapter()
    private var lastSnapshot = EngineSnapshot.Empty
    private var revision = 0L
    private var pending: PageDirection? = null
    private var dispatchTask: Runnable? = null
    private var attempted: Pair<EngineSnapshot, PageDirection>? = null
    private var explicitRequest = false
    private val prefetch = Runnable { prefetch(PageDirection.NEXT) }
    private val scroll = RecyclerView(context).apply {
        layoutManager = layout
        adapter = this@ExpandedCandidatesView.adapter
        setHasFixedSize(true)
        itemAnimator = null
        isSaveEnabled = false
        setItemViewCacheSize(6)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                updateNavigation()
                if (dy != 0) prefetch(if (dy > 0) PageDirection.NEXT else PageDirection.PREVIOUS)
            }
        })
    }
    private val previous = panelIconButton(context, android.R.drawable.ic_media_previous, R.string.previous_candidates) {
        browse(PageDirection.PREVIOUS)
    }
    private val next = panelIconButton(context, android.R.drawable.ic_media_next, R.string.next_candidates) {
        browse(PageDirection.NEXT)
    }

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LayoutParams(dp(48), dp(48)))
            addView(Space(context), LayoutParams(0, 1, 1f))
            addView(next, LayoutParams(dp(48), dp(48)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val columns = (width / dp(112)).coerceIn(2, 4)
        if (layout.spanCount != columns) layout.spanCount = columns
        schedulePrefetch()
    }

    fun render(snapshot: EngineSnapshot) {
        dispatchTask?.let(::removeCallbacks)
        dispatchTask = null
        val request = pending
        val explicit = explicitRequest
        pending = null
        explicitRequest = false
        if (snapshot == lastSnapshot) return
        val position = layout.findFirstVisibleItemPosition()
        val anchor = adapter.items.getOrNull(position)?.id
        val offset = layout.findViewByPosition(position)?.top ?: 0
        val old = adapter.items
        val sameInput = snapshot.rawInput == lastSnapshot.rawInput && snapshot.composition == lastSnapshot.composition
        val oldIds = old.mapTo(HashSet()) { it.id }
        val newPage = snapshot.candidates.indexOfFirst { it.id !in oldIds }
        val removedHead = old.firstOrNull()?.id?.let { id -> snapshot.candidates.none { it.id == id } } == true
        lastSnapshot = snapshot
        revision++
        adapter.replace(snapshot.candidates, snapshot.highlightedIndex)
        val preserved = if (sameInput) snapshot.candidates.indexOfFirst { it.id == anchor } else -1
        when {
            !sameInput -> { scroll.stopScroll(); layout.scrollToPositionWithOffset(0, 0) }
            explicit && newPage >= 0 -> layout.scrollToPositionWithOffset(newPage, 0)
            preserved >= 0 && preserved != position -> layout.scrollToPositionWithOffset(preserved, offset)
        }
        updateNavigation()
        // Stop filling when the bounded core window starts evicting rows.
        if (!removedHead && request != PageDirection.PREVIOUS) schedulePrefetch()
    }

    private fun schedulePrefetch() {
        removeCallbacks(prefetch)
        post(prefetch)
    }

    private fun prefetch(direction: PageDirection) {
        if (!isShown || scroll.height == 0 || adapter.itemCount == 0) return
        val margin = layout.spanCount * 2
        val nearEdge = if (direction == PageDirection.NEXT)
            layout.findLastVisibleItemPosition() >= adapter.itemCount - 1 - margin
        else layout.findFirstVisibleItemPosition() in 0..margin
        if (nearEdge) requestPage(direction, explicit = false)
    }

    private fun browse(direction: PageDirection) {
        val step = if (direction == PageDirection.NEXT) 1 else -1
        val available = if (direction == PageDirection.NEXT) lastSnapshot.hasNextPage else lastSnapshot.hasPreviousPage
        if (available) requestPage(direction, explicit = true)
        else if (scroll.canScrollVertically(step)) scroll.smoothScrollBy(0, step * scroll.height.coerceAtLeast(dp(48)))
    }

    private fun requestPage(direction: PageDirection, explicit: Boolean) {
        val available = if (direction == PageDirection.NEXT) lastSnapshot.hasNextPage else lastSnapshot.hasPreviousPage
        if (!available || !explicit && (pending != null || attempted == (lastSnapshot to direction))) return
        dispatchTask?.let(::removeCallbacks)
        pending = direction
        explicitRequest = explicit
        attempted = lastSnapshot to direction
        val expected = revision
        // Never mutate the adapter from RecyclerView's layout/scroll callback.
        val dispatch = Runnable {
            dispatchTask = null
            if (revision == expected && isShown) onPageChanged(direction)
            if (revision == expected) { pending = null; explicitRequest = false }
        }
        dispatchTask = dispatch
        if (!explicit || scroll.isComputingLayout) post(dispatch) else dispatch.run()
    }

    private fun updateNavigation() {
        previous.isEnabled = lastSnapshot.hasPreviousPage || scroll.canScrollVertically(-1)
        next.isEnabled = lastSnapshot.hasNextPage || scroll.canScrollVertically(1)
        previous.alpha = if (previous.isEnabled) 1f else 0.35f
        next.alpha = if (next.isEnabled) 1f else 0.35f
    }

    fun clear() {
        removeCallbacks(prefetch)
        dispatchTask?.let(::removeCallbacks)
        dispatchTask = null
        scroll.stopScroll()
        revision++
        pending = null
        attempted = null
        render(EngineSnapshot.Empty)
        for (index in 0 until scroll.childCount) (scroll.getChildAt(index) as? CandidateItemView)?.clear()
    }

    private inner class CandidateAdapter : RecyclerView.Adapter<CandidateHolder>() {
        var items: List<Candidate> = emptyList()
            private set
        private var highlighted = 0

        fun replace(values: List<Candidate>, selected: Int) {
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
            changes.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateHolder =
            CandidateHolder(CandidateItemView(parent.context).apply {
                setSingleLine(false)
                maxLines = 2
                textSize = 16f
                layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
                onSelected = { onCandidateSelected(it) }
            })

        override fun onBindViewHolder(holder: CandidateHolder, position: Int) {
            holder.view.bind(items[position], position, position == highlighted)
        }

        override fun onViewRecycled(holder: CandidateHolder) { holder.view.clear() }
    }

    private class CandidateHolder(val view: CandidateItemView) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
