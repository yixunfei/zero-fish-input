package dev.zeroinput.ime.expressions

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.PersonalExpression

internal class ExpressionManagerAdapter(
    private val onEdit: (PersonalExpression) -> Unit,
    private val onDelete: (PersonalExpression) -> Unit,
) : RecyclerView.Adapter<ExpressionManagerAdapter.Holder>() {
    private var entries = emptyList<PersonalExpression>()
    private var revision = 0L
    private val bound = mutableSetOf<Holder>()
    fun submit(values: List<PersonalExpression>) {
        revision++
        bound.forEach {
            it.value.text = ""
            it.details.text = ""
            it.texts.contentDescription = null
            it.texts.setOnClickListener(null)
            it.delete.setOnClickListener(null)
        }
        val previous = entries
        val next = values.toList()
        entries = next
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].id == next[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == next[newItemPosition]
        }).dispatchUpdatesTo(this)
    }
    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isSaveEnabled = false
        }
        val texts = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            filterTouchesWhenObscured = true
            minimumHeight = (64 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = TextView(parent.context).apply { textSize = 18f; isSaveEnabled = false }
        val details = TextView(parent.context).apply { textSize = 13f; isSaveEnabled = false }
        texts.addView(value)
        texts.addView(details)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val delete = AppCompatImageButton(parent.context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = context.getString(R.string.expression_delete_custom)
            tooltipText = contentDescription
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            filterTouchesWhenObscured = true
        }
        row.addView(delete, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        return Holder(row, texts, value, details, delete)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        bound += holder
        val entry = entries[position]
        val expected = revision
        holder.value.text = entry.value
        holder.details.text = listOf(entry.name, entry.keywords).filter(String::isNotBlank).joinToString(" · ")
        holder.texts.contentDescription = holder.itemView.context.getString(R.string.expression_edit_named, entry.name)
        holder.texts.setOnClickListener { if (revision == expected) onEdit(entry) }
        holder.delete.setOnClickListener { if (revision == expected) onDelete(entry) }
    }

    override fun onViewRecycled(holder: Holder) {
        bound -= holder
        holder.value.text = ""
        holder.details.text = ""
        holder.texts.contentDescription = null
        holder.texts.setOnClickListener(null)
        holder.delete.setOnClickListener(null)
        super.onViewRecycled(holder)
    }

    class Holder(row: LinearLayout, val texts: LinearLayout, val value: TextView, val details: TextView,
        val delete: AppCompatImageButton) : RecyclerView.ViewHolder(row)
}
