package dev.zeroinput.ime.ui

import android.widget.TextView
import androidx.appcompat.widget.PopupMenu

/** Only saved model identifiers enter the UI; stale menus cannot change a new session. */
internal class AiModelMenu(
    private val anchor: TextView,
    private val select: (String) -> Unit,
    private val settings: () -> Unit,
) {
    private var models = emptyList<String>()
    private var selected: String? = null
    private var revision = 0L
    private var popup: PopupMenu? = null

    fun render(values: List<String>, current: String?) {
        dismiss()
        models = values.take(32).toList()
        selected = current?.takeIf { it in models }
        anchor.text = selected?.let { anchor.context.getString(R.string.ai_current_model, it) }
            ?: anchor.context.getString(R.string.ai_switch_model)
        anchor.contentDescription = selected?.let { anchor.context.getString(R.string.ai_switch_named_model, it) }
            ?: anchor.context.getString(R.string.ai_switch_model)
    }

    fun show() {
        dismiss()
        if (models.isEmpty()) { settings(); return }
        val token = revision
        val entries = models
        popup = PopupMenu(anchor.context, anchor).also { menu ->
            entries.forEachIndexed { index, model ->
                menu.menu.add(0, index, index, model).apply { isCheckable = true; isChecked = model == selected }
            }
            menu.setOnMenuItemClickListener {
                if (token == revision) entries.getOrNull(it.itemId)?.let(select)
                true
            }
            menu.show()
        }
    }

    fun dismiss() { revision++; popup?.dismiss(); popup = null }
}
