package dev.zeroinput.ime.settings

import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors

internal fun MaterialButtonToggleGroup.addWrappingModeButton(button: MaterialButton) {
    addView(button)
    // ToggleGroup forces one line during addView; restore wrapping afterwards.
    button.setSingleLine(false)
    button.maxLines = Int.MAX_VALUE
    button.ellipsize = null
    button.layoutParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
}

/** Applies a clear checked state to every mutually exclusive settings choice. */
internal fun MaterialButton.applySettingsModeStyle() {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    backgroundTintList = ColorStateList(states, intArrayOf(
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY),
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE),
    ))
    setTextColor(ColorStateList(states, intArrayOf(
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK),
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK),
    )))
    strokeWidth = (1 * resources.displayMetrics.density).toInt()
    strokeColor = ColorStateList.valueOf(MaterialColors.getColor(
        context, com.google.android.material.R.attr.colorOutline, Color.GRAY,
    ))
}
