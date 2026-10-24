package dev.zeroinput.ime.settings

import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

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
