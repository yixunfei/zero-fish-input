package dev.zeroinput.ime.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.zeroinput.engine.api.Candidate

data class ShapeChoice(val character: String, val pinyin: String = "")

internal class ShapeLookupPanelView(context: Context) : LinearLayout(context) {
    var onChoice: (Int, String) -> Unit = { _, _ -> }
    var onReset: () -> Unit = {}

    private var appearance = ExpandedCandidateAppearance()
    private var choices = emptyList<ShapeChoice>()
    private var renderRevision = 0L
    private val separators = CandidateSeparators()
    private val grid = GridLayoutManager(context, 1)
    private val candidates = ShapeAdapter()
    private val status = TextView(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val reset = panelIconButton(context, R.drawable.ic_expression_close, R.string.shape_reset) { onReset() }
    private val list = RecyclerView(context).apply {
        layoutManager = grid
        adapter = candidates
        itemAnimator = null
        setHasFixedSize(true)
        isSaveEnabled = false
        addItemDecoration(separators)
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LayoutParams(0, dp(48), 1f))
            addView(reset, LayoutParams(dp(48), dp(48)))
        })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun render(query: String, seed: String?, choices: List<ShapeChoice>, loading: Boolean, failed: Boolean) {
        status.text = when {
            failed -> context.getString(R.string.shape_failed)
            loading -> context.getString(R.string.shape_loading)
            choices.isEmpty() -> context.getString(R.string.shape_empty)
            seed != null -> seed
            query.isNotEmpty() -> query
            else -> context.getString(R.string.shape_choose_radical)
        }
        reset.isVisible = seed != null || query.isNotEmpty()
        this.choices = choices
        renderRevision++
        candidates.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    fun applyAppearance(value: ExpandedCandidateAppearance) {
        if (appearance == value) return
        appearance = value
        separators.enabled = value.separators
        updateColumns(width)
        candidates.notifyDataSetChanged()
        list.invalidateItemDecorations()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateColumns(w)
    }

    private fun updateColumns(width: Int) {
        grid.spanCount = if (appearance.layout == ExpandedCandidateLayout.LIST) 1
            else (width / dp(appearance.density.minimumWidth)).coerceAtLeast(1)
    }

    private inner class ShapeAdapter : RecyclerView.Adapter<ShapeHolder>() {
        override fun getItemCount(): Int = choices.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShapeHolder =
            ShapeHolder(CandidateItemView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                setSingleLine(false)
                maxLines = 2
            })

        override fun onBindViewHolder(holder: ShapeHolder, position: Int) {
            val choice = choices[position]
            val revision = renderRevision
            holder.cell.apply {
                applyExpandedAppearance(appearance)
                bind(Candidate("$revision:${choice.character}", choice.character), position, highlighted = false)
                val reading = choice.pinyin.substringBefore(" · ")
                if (reading.isNotEmpty()) {
                    val label = "${choice.character}\n$reading"
                    text = SpannableString(label).apply {
                        setSpan(RelativeSizeSpan(0.67f), choice.character.length + 1, length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                contentDescription = "${choice.character} ${choice.pinyin}".trimEnd()
                onSelected = { index ->
                    if (renderRevision == revision && holder.bindingAdapterPosition == index) {
                        onChoice(index, choice.character)
                    }
                }
            }
        }

        override fun onViewRecycled(holder: ShapeHolder) {
            holder.cell.clear()
        }
    }

    private class ShapeHolder(val cell: CandidateItemView) : RecyclerView.ViewHolder(cell)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
