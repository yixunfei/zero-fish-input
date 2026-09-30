package dev.zeroinput.ime.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.withStyledAttributes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/** Public variants use the same offline artwork and never depend on personalization permission. */
internal class EmojiVariantPopup(
    private val anchor: View,
    private val entries: List<EmojiEntry>,
    private val selected: (EmojiEntry) -> Unit,
) {
    private val context = anchor.context
    private val density = anchor.resources.displayMetrics.density
    private val width = minOf(dp(336), anchor.rootView.width.coerceAtLeast(dp(144)))
    private val columns = (width / dp(48)).coerceIn(3, 7)
    private val rows = ((entries.size + columns - 1) / columns).coerceIn(1, 4)
    private val popup = PopupWindow(width, dp(48 + rows * 48)).apply {
        isFocusable = true
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(ColorDrawable(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface)))
        elevation = dp(8).toFloat()
    }

    init {
        popup.contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
            addView(AppCompatImageButton(context).apply {
                setImageResource(R.drawable.ic_expression_close)
                imageTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(anchor,
                    com.google.android.material.R.attr.colorOnSurface))
                contentDescription = context.getString(R.string.expression_close_variants)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.END })
            addView(RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, columns)
                adapter = VariantAdapter()
                itemAnimator = null
                isSaveEnabled = false
                contentDescription = context.getString(R.string.expression_variants)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    fun show() {
        if (anchor.isAttachedToWindow && entries.isNotEmpty()) popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    fun dismiss() { popup.dismiss() }

    private inner class VariantAdapter : RecyclerView.Adapter<EmojiAdapter.Holder>() {
        override fun getItemCount(): Int = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiAdapter.Holder =
            EmojiAdapter.Holder(ExpressionCellView(context).apply {
                gravity = Gravity.CENTER
                textSize = 26f
                isClickable = true
                isFocusable = true
                isSaveEnabled = false
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                context.withStyledAttributes(attrs = intArrayOf(android.R.attr.selectableItemBackground)) { background = getDrawable(0) }
            })

        override fun onBindViewHolder(holder: EmojiAdapter.Holder, position: Int) {
            val entry = entries[position]
            holder.text.bindingRevision = position.toLong()
            holder.text.bindExpression(entry)
            holder.text.contentDescription = entry.displayName(context.resources.configuration.locales[0])
            holder.text.setOnClickListener {
                if (popup.isShowing && holder.bindingAdapterPosition == position) {
                    dismiss()
                    selected(entry)
                }
            }
        }

        override fun onViewRecycled(holder: EmojiAdapter.Holder) {
            holder.text.bindingRevision = -1L
            holder.text.bindExpression(null)
            holder.text.contentDescription = null
            holder.text.setOnClickListener(null)
            super.onViewRecycled(holder)
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
