package dev.zeroinput.ime.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection

internal class ExpandedCandidatesView(context: Context) : LinearLayout(context) {
    var onCandidateSelected: (Int) -> Unit = {}
    var onPageChanged: (PageDirection) -> Unit = {}
    private val layout = GridLayoutManager(context, 3)
    private val adapter = CandidateAdapter()
    private val scroll = RecyclerView(context).apply {
        layoutManager = layout
        adapter = this@ExpandedCandidatesView.adapter
        setHasFixedSize(true)
        itemAnimator = null
        setItemViewCacheSize(6)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !view.canScrollVertically(1)) requestPage(PageDirection.NEXT, defer = true)
                if (dy < 0 && !view.canScrollVertically(-1)) requestPage(PageDirection.PREVIOUS, defer = true)
            }
        })
    }
    private val previous = panelIconButton(context, android.R.drawable.ic_media_previous, R.string.previous_candidates) {
        requestPage(PageDirection.PREVIOUS)
    }
    private val next = panelIconButton(context, android.R.drawable.ic_media_next, R.string.next_candidates) {
        requestPage(PageDirection.NEXT)
    }
    private val browser = HorizontalSwipeFrameLayout(context, SwipeAxis.VERTICAL).apply {
        canSwipe = { direction ->
            val step = if (direction == PageDirection.NEXT) 1 else -1
            val available = if (direction == PageDirection.NEXT) lastSnapshot.hasNextPage
            else lastSnapshot.hasPreviousPage
            available && !scroll.canScrollVertically(step)
        }
        onSwipe = { direction ->
            requestPage(direction)
        }
        addView(scroll)
    }
    private var lastSnapshot = EngineSnapshot.Empty
    private var pending = false
    private var revision = 0L
    private var requestedPage: PageDirection? = null

    init {
        orientation = VERTICAL
        addView(browser, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LayoutParams(dp(48), dp(48)))
            addView(Space(context), LayoutParams(0, 1, 1f))
            addView(next, LayoutParams(dp(48), dp(48)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // Keep cells readable on split-screen phones while using the extra
        // width available in landscape.  The bounded range preserves stable
        // touch targets and avoids a layout jump for ordinary portrait widths.
        val columns = (width / dp(112)).coerceIn(2, 4)
        if (layout.spanCount != columns) layout.spanCount = columns
    }

    fun render(snapshot: EngineSnapshot) {
        pending = false
        val pageRequest = requestedPage
        requestedPage = null
        if (snapshot == lastSnapshot) return
        browser.cancelSwipe()
        val position = layout.findFirstVisibleItemPosition()
        val anchor = adapter.items.getOrNull(position)?.id
        val offset = layout.findViewByPosition(position)?.top ?: 0
        val sameInput = snapshot.rawInput == lastSnapshot.rawInput && snapshot.composition == lastSnapshot.composition
        val oldTexts = lastSnapshot.candidates.map { it.text }.toSet()
        val newPageAnchor = if (pageRequest == PageDirection.NEXT)
            snapshot.candidates.indexOfFirst { it.text !in oldTexts } else -1
        lastSnapshot = snapshot
        revision++
        adapter.replace(snapshot.candidates, snapshot.highlightedIndex)
        val preserved = if (sameInput) snapshot.candidates.indexOfFirst { it.id == anchor } else -1
        when {
            newPageAnchor >= 0 -> layout.scrollToPositionWithOffset(newPageAnchor, 0)
            pageRequest == PageDirection.PREVIOUS -> layout.scrollToPositionWithOffset(0, 0)
            else -> layout.scrollToPositionWithOffset(preserved.coerceAtLeast(0), if (preserved >= 0) offset else 0)
        }
        previous.isEnabled = snapshot.hasPreviousPage
        previous.alpha = if (previous.isEnabled) 1f else 0.35f
        next.isEnabled = snapshot.hasNextPage
        next.alpha = if (next.isEnabled) 1f else 0.35f
    }

    private fun requestPage(direction: PageDirection, defer: Boolean = false) {
        val available = if (direction == PageDirection.NEXT) lastSnapshot.hasNextPage else lastSnapshot.hasPreviousPage
        if (pending || !available) return
        pending = true
        val expected = revision
        // Paging can render synchronously. Always leave RecyclerView's scroll
        // callback before notifying the adapter.
        val dispatch = {
            if (revision == expected && isShown) {
                requestedPage = direction
                onPageChanged(direction)
                // Keep the direction through a callback that posts its render,
                // while avoiding a stale anchor when no update is delivered.
                if (revision == expected && requestedPage == direction) post {
                    if (revision == expected && requestedPage == direction) requestedPage = null
                }
            }
            pending = false
        }
        if (defer || scroll.isComputingLayout) post(dispatch) else dispatch()
    }

    fun clear() {
        browser.cancelSwipe()
        revision++
        render(EngineSnapshot.Empty)
        for (index in 0 until scroll.childCount) (scroll.getChildAt(index) as? CandidateItemView)?.clear()
    }

    private inner class CandidateAdapter : RecyclerView.Adapter<CandidateHolder>() {
        var items: List<Candidate> = emptyList()
            private set
        private var highlighted = 0

        fun replace(values: List<Candidate>, selected: Int) {
            items = values
            highlighted = selected
            // Paging replaces a bounded, non-animating grid. A full refresh
            // avoids DiffUtil's synchronous O(n) comparison on every gesture.
            notifyDataSetChanged()
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
