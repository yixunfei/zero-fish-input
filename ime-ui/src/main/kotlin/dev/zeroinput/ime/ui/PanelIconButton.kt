package dev.zeroinput.ime.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.color.MaterialColors

internal fun panelIconButton(context: Context, icon: Int, description: Int, action: () -> Unit) =
    AppCompatImageButton(context).apply {
        setImageResource(icon)
        contentDescription = context.getString(description)
        TooltipCompat.setTooltipText(this, contentDescription)
        val backgroundValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
        setBackgroundResource(backgroundValue.resourceId)
        imageTintList = ColorStateList.valueOf(MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        val padding = (14 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setOnClickListener { action() }
    }
