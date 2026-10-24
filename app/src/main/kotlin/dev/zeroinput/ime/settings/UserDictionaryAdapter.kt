package dev.zeroinput.ime.settings

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.AppCompatImageButton
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.UserTerm

class UserDictionaryAdapter(
    private val onEdit: (UserTerm) -> Unit = {},
    private val onDelete: (UserTerm) -> Unit,
) : ListAdapter<UserTerm, UserDictionaryAdapter.Holder>(Difference) {

    fun submit(values: List<UserTerm>) {
        submitList(values.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (4 * density).toInt(), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * density).toInt())
        }
        val textGroup = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val value = TextView(parent.context).apply {
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val details = TextView(parent.context).apply {
            textSize = 12f
            alpha = 0.64f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textGroup.addView(value)
        textGroup.addView(details)
        val remove = AppCompatImageButton(parent.context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = context.getString(R.string.user_dictionary_delete)
            val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            try {
                setBackgroundResource(attributes.getResourceId(0, 0))
            } finally {
                attributes.recycle()
            }
            filterTouchesWhenObscured = true
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
        }
        row.addView(textGroup)
        row.addView(remove)
        return Holder(row, value, details, remove)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val term = getItem(position)
        holder.itemView.setOnClickListener { onEdit(term) }
        holder.value.text = term.value
        val language = holder.itemView.context.getString(
            when (term.language) {
                InputLanguage.CHINESE -> R.string.language_chinese
                InputLanguage.ENGLISH -> R.string.language_english
            },
        )
        holder.details.text = holder.itemView.context.getString(
            R.string.user_dictionary_item_details,
            term.shortcut,
            language,
            term.frequency,
        )
        holder.remove.setOnClickListener { onDelete(term) }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.value.text = ""
        holder.details.text = ""
        holder.remove.setOnClickListener(null)
        holder.itemView.setOnClickListener(null)
        super.onViewRecycled(holder)
    }

    class Holder(
        itemView: LinearLayout,
        val value: TextView,
        val details: TextView,
        val remove: AppCompatImageButton,
    ) : RecyclerView.ViewHolder(itemView)

    private object Difference : DiffUtil.ItemCallback<UserTerm>() {
        override fun areItemsTheSame(oldItem: UserTerm, newItem: UserTerm): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UserTerm, newItem: UserTerm): Boolean = oldItem == newItem
    }
}
