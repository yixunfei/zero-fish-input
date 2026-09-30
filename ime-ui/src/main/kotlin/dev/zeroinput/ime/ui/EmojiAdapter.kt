package dev.zeroinput.ime.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView

internal class EmojiAdapter(
    private val onSelected: (EmojiEntry) -> Unit,
    private val onFavorite: (EmojiEntry, Boolean) -> Unit,
    private val onEdit: (String) -> Unit,
    private val onInteraction: () -> Unit,
) : RecyclerView.Adapter<EmojiAdapter.Holder>() {
    private var values = emptyList<EmojiEntry>()
    private var favorites = emptySet<String>()
    private var allowPersonal = false
    private var revision = 0L
    private var popup: PopupMenu? = null
    private var variantPopup: EmojiVariantPopup? = null
    private val bound = mutableSetOf<Holder>()

    fun submit(entries: List<EmojiEntry>, starred: Set<String>, allowed: Boolean) {
        if (entries == values && favorites == starred && allowPersonal == allowed) return
        revision++
        popup?.dismiss()
        popup = null
        variantPopup?.dismiss()
        variantPopup = null
        bound.forEach { holder ->
            holder.text.bindExpression(null)
            holder.text.contentDescription = null
            holder.text.setOnClickListener(null)
            holder.text.setOnLongClickListener(null)
            ViewCompat.removeAccessibilityAction(holder.text, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK.id)
        }
        val oldCount = values.size
        values = emptyList()
        if (oldCount > 0) notifyItemRangeRemoved(0, oldCount)
        values = entries.toList()
        favorites = starred.toSet()
        allowPersonal = allowed
        // Immediate replacement prevents a background diff from retaining private rows after revocation.
        if (values.isNotEmpty()) notifyItemRangeInserted(0, values.size)
    }

    fun entry(position: Int): EmojiEntry? = values.getOrNull(position)
    override fun getItemCount(): Int = values.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val view = ExpressionCellView(parent.context).apply {
            gravity = Gravity.CENTER
            minHeight = (48 * density).toInt()
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            isSaveEnabled = false
            letterSpacing = 0f
            context.withStyledAttributes(attrs = intArrayOf(android.R.attr.selectableItemBackground)) { background = getDrawable(0) }
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        bound += holder
        val entry = values[position]
        val boundRevision = revision
        val view = holder.text
        view.bindingRevision = revision
        view.bindExpression(entry)
        view.textSize = if (entry.isWide) 18f else 26f
        view.maxLines = if (entry.isWide) 12 else 1
        view.contentDescription = if (entry.customId != null) "${entry.name} ${entry.value}"
            else entry.displayName(view.resources.configuration.locales[0])
        view.isActivated = entry.value in favorites
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
            if (entry.value in favorites) R.drawable.ic_expression_star_small else 0, 0)
        fun current() = boundRevision == revision &&
            values.getOrNull(holder.bindingAdapterPosition) == entry
        view.setOnClickListener { if (current()) onSelected(entry) }
        view.setOnLongClickListener {
            if (current() && (allowPersonal || EmojiCatalog.variants(entry).size > 1)) showMenu(view, entry, boundRevision)
            true
        }
        ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            view.context.getString(R.string.expression_actions)) { _, _ ->
            if (current() && (allowPersonal || EmojiCatalog.variants(entry).size > 1)) {
                showMenu(view, entry, boundRevision); true
            } else false
        }
    }

    override fun onViewRecycled(holder: Holder) {
        bound -= holder
        holder.text.bindExpression(null)
        holder.text.contentDescription = null
        holder.text.setOnClickListener(null)
        holder.text.setOnLongClickListener(null)
        holder.text.bindingRevision = -1L
        ViewCompat.removeAccessibilityAction(holder.text, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK.id)
        super.onViewRecycled(holder)
    }

    private fun showMenu(view: TextView, entry: EmojiEntry, boundRevision: Long) {
        onInteraction()
        val selected = entry.value in favorites
        popup?.dismiss()
        popup = PopupMenu(view.context, view).apply {
            if (allowPersonal) {
                menu.add(0, 1, 0, if (selected) R.string.expression_unfavorite else R.string.expression_favorite)
                if (entry.customId != null) menu.add(0, 2, 1, R.string.expression_edit)
            }
            if (EmojiCatalog.variants(entry).size > 1) menu.add(0, 3, 2, R.string.expression_variants)
            setOnMenuItemClickListener { item ->
                if (boundRevision == revision) when (item.itemId) {
                    1 -> if (allowPersonal) onFavorite(entry, !selected)
                    2 -> if (allowPersonal) entry.customId?.let(onEdit)
                    3 -> showVariants(view, entry, boundRevision)
                }
                true
            }
            show()
        }
    }

    private fun showVariants(view: TextView, entry: EmojiEntry, boundRevision: Long) {
        variantPopup?.dismiss()
        variantPopup = EmojiVariantPopup(view, EmojiCatalog.variants(entry)) { variant ->
            if (boundRevision == revision) onSelected(variant)
        }.also { it.show() }
    }

    class Holder(val text: ExpressionCellView) : RecyclerView.ViewHolder(text)
}
