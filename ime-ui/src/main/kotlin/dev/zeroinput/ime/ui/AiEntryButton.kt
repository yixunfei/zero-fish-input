package dev.zeroinput.ime.ui

import android.content.Context
import android.view.View
import androidx.appcompat.widget.TooltipCompat

/** An unavailable entry explains the restriction; it never opens a private workbench. */
internal fun aiEntryButton(context: Context, action: () -> Unit) =
    panelIconButton(context, R.drawable.ic_ai_assistant, R.string.ai_open, action)

internal fun View.renderAiEntryAvailability(available: Boolean) {
    val description = context.getString(if (available) R.string.ai_open else R.string.ai_entry_unavailable)
    if (contentDescription == description) return
    alpha = if (available) 1f else 0.5f
    contentDescription = description
    TooltipCompat.setTooltipText(this, contentDescription)
}
