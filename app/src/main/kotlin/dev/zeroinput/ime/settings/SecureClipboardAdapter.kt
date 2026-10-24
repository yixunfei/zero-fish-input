package dev.zeroinput.ime.settings

import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.SecureClipboardMetadata

internal class SecureClipboardAdapter(
    private val onDelete: (SecureClipboardMetadata) -> Unit,
) : ListAdapter<SecureClipboardMetadata, SecureClipboardAdapter.Holder>(Difference) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (72 * density).toInt())
        }
        val texts = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val label = TextView(parent.context).apply {
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val details = TextView(parent.context).apply {
            textSize = 12f
            alpha = 0.68f
            maxLines = 1
        }
        texts.addView(label)
        texts.addView(details)
        val delete = AppCompatImageButton(parent.context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = context.getString(R.string.delete_secure_item)
            setBackgroundResource(selectableBorderlessBackground(context))
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
            filterTouchesWhenObscured = true
        }
        row.addView(texts)
        row.addView(delete)
        return Holder(row, label, details, delete)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.label.text = item.label
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            item.updatedAtEpochMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        holder.details.text = holder.itemView.context.getString(
            R.string.secure_item_details,
            item.valueLength,
            relativeTime,
        )
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.clearMetadata()
    }

    private fun selectableBorderlessBackground(context: android.content.Context): Int {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        return values.getResourceId(0, 0).also { values.recycle() }
    }

    class Holder(
        itemView: LinearLayout,
        val label: TextView,
        val details: TextView,
        val delete: AppCompatImageButton,
    ) : RecyclerView.ViewHolder(itemView) {
        fun clearMetadata() {
            label.text = ""
            details.text = ""
            delete.setOnClickListener(null)
        }
    }

    private object Difference : DiffUtil.ItemCallback<SecureClipboardMetadata>() {
        override fun areItemsTheSame(oldItem: SecureClipboardMetadata, newItem: SecureClipboardMetadata): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SecureClipboardMetadata, newItem: SecureClipboardMetadata): Boolean =
            oldItem == newItem
    }
}
