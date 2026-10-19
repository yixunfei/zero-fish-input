package dev.zeroinput.ime.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import dev.zeroinput.ai.api.AiResultSections

/** Lets the user choose exact plain-text response sections before insertion. */
internal class AiResultSelectionView(context: Context) : LinearLayout(context) {
    var onSelectionChanged: (List<Int>) -> Unit = {}
    private var rendering = false
    private var selected = emptySet<Int>()
    private val checks = mutableListOf<MaterialCheckBox>()
    private val count = TextView(context).apply { textSize = 12f; isSaveEnabled = false }
    private val all = MaterialCheckBox(context).apply {
        setText(R.string.ai_select_all)
        isSaveEnabled = false
        minHeight = dp(48)
        setOnCheckedChangeListener { _, checked -> selectAll(checked) }
    }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val controls = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(all, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(button(R.string.ai_clear_selection, ::clearSelection), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(count)
        addView(controls)
        addView(rows)
        visibility = GONE
    }

    val selection: List<Int> get() = selected.sorted()

    fun render(value: String) {
        val sections = AiResultSections.split(value)
        rendering = true
        try {
            selected = emptySet()
            releaseChecks()
            sections.forEach { section ->
                val check = MaterialCheckBox(context).apply {
                    text = section.text
                    isChecked = false
                    isSaveEnabled = false
                    minHeight = dp(48)
                    setOnCheckedChangeListener { _, checked -> update(section.index, checked) }
                }
                checks += check
                rows.addView(check)
            }
            visibility = if (sections.isEmpty()) GONE else VISIBLE
            all.isChecked = false
            updateCount()
        } finally {
            rendering = false
        }
        onSelectionChanged(selection)
    }

    fun clear() {
        rendering = true
        try {
            releaseChecks()
            selected = emptySet()
            all.isChecked = false
            visibility = GONE
        } finally {
            rendering = false
        }
        onSelectionChanged(emptyList())
    }

    private fun releaseChecks() {
        checks.forEach { it.setOnCheckedChangeListener(null); it.text = "" }
        checks.clear()
        rows.removeAllViews()
    }

    private fun selectAll(value: Boolean) {
        if (rendering) return
        rendering = true
        try { checks.forEach { it.isChecked = value } } finally { rendering = false }
        selected = if (value) checks.indices.toSet() else emptySet()
        updateCount()
        onSelectionChanged(selection)
    }

    private fun clearSelection() {
        selectAll(false)
        rendering = true
        try { all.isChecked = false } finally { rendering = false }
    }

    private fun update(index: Int, checked: Boolean) {
        if (rendering) return
        selected = if (checked) selected + index else selected - index
        rendering = true
        try { all.isChecked = selected.size == checks.size && checks.isNotEmpty() }
        finally { rendering = false }
        updateCount()
        onSelectionChanged(selection)
    }

    private fun updateCount() {
        count.text = context.getString(R.string.ai_selected_sections, selected.size, checks.size)
    }

    private fun button(label: Int, action: () -> Unit) = android.widget.Button(context).apply {
        setText(label)
        minHeight = dp(48)
        isSaveEnabled = false
        setOnClickListener { if (isEnabled) action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
