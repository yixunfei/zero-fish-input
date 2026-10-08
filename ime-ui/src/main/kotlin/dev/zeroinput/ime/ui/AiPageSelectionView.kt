package dev.zeroinput.ime.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox

/** Snapshot indices are resolved by the request owner, never trusted as arbitrary source text. */
internal class AiPageSelectionView(context: Context) : LinearLayout(context) {
    var onSelectionChanged: (Boolean) -> Unit = {}
    val selection: List<Int> get() = selected.sorted()
    private val selected = linkedSetOf<Int>()

    init { orientation = VERTICAL; isSaveEnabled = false }

    fun render(texts: List<String>?, incomplete: Boolean = false, loading: Boolean = false) {
        removeAllViews()
        selected.clear()
        onSelectionChanged(false)
        addView(TextView(context).apply {
            setText(when {
                loading -> R.string.ai_page_loading
                texts == null -> R.string.ai_page_failed
                texts.isEmpty() -> R.string.ai_page_empty
                incomplete -> R.string.ai_page_partial
                else -> R.string.ai_page_choose
            })
        })
        texts.orEmpty().forEachIndexed { index, text ->
            addView(MaterialCheckBox(context).apply {
                val preview = referencePreview(text)
                this.text = context.getString(R.string.ai_page_reference_item, index + 1, text.length, preview)
                isSaveEnabled = false
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += index else selected -= index
                    onSelectionChanged(selected.isNotEmpty())
                }
            })
        }
    }

}
