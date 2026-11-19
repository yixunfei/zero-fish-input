package dev.zeroinput.ime.ui

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

data class StegoAlgorithmUi(val id: Int, val name: String, val bits: Int, val order: Int, val adjustable: Boolean)

/** Public algorithm descriptors only. Selection order is supplied by the workspace. */
internal class StegoAlgorithmView(context: Context) : LinearLayout(context) {
    var onSelect: (Int, Boolean) -> Unit = { _, _ -> }
    var onWidth: (Int) -> Unit = {}
    var onEarlier: (Int) -> Unit = {}
    private data class Row(val check: CheckBox, val width: MaterialButton, val earlier: MaterialButton)
    private val rows = mutableMapOf<Int, Row>()

    init { orientation = VERTICAL; isSaveEnabled = false }

    fun render(values: List<StegoAlgorithmUi>, editable: Boolean) {
        if (rows.keys != values.map { it.id }.toSet()) {
            removeAllViews(); rows.clear()
            values.forEach { value ->
                val check = CheckBox(context).apply {
                    isSaveEnabled = false
                    textSize = 13f
                    filterTouchesWhenObscured = true
                    minHeight = dp(48)
                    setOnClickListener { onSelect(value.id, isChecked) }
                }
                val width = button { onWidth(value.id) }
                val earlier = button { onEarlier(value.id) }.apply { setText(R.string.stego_earlier) }
                addView(LinearLayout(context).apply {
                    isBaselineAligned = false
                    addView(check, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    addView(width, LayoutParams(dp(64), dp(48)))
                    addView(earlier, LayoutParams(dp(56), dp(48)))
                })
                rows[value.id] = Row(check, width, earlier)
            }
        }
        values.forEach { value ->
            val row = rows.getValue(value.id)
            row.check.text = if (value.order >= 0) "${value.order + 1}. ${value.name}" else value.name
            row.check.isChecked = value.order >= 0
            row.check.isEnabled = editable
            row.width.text = value.bits.toString()
            row.width.contentDescription = context.getString(R.string.stego_width_for, value.name, value.bits)
            row.width.isEnabled = editable && value.order >= 0 && value.adjustable
            row.earlier.contentDescription = context.getString(R.string.stego_earlier_for, value.name)
            row.earlier.isEnabled = editable && value.order > 0
        }
    }

    private fun button(action: () -> Unit) = MaterialButton(context, null,
        com.google.android.material.R.attr.borderlessButtonStyle).apply {
        isAllCaps = false; minWidth = 0; minimumWidth = 0
        textSize = 13f; letterSpacing = 0f; insetTop = 0; insetBottom = 0
        setPadding(0, 0, 0, 0); maxLines = 2
        filterTouchesWhenObscured = true
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
