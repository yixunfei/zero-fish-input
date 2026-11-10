package dev.zeroinput.ime.ai

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiSkillRepository
import java.util.concurrent.Executor

/** Read-only selection. The manager owns edits, and selected definitions are re-read before apply. */
internal class AiSkillSelectionDialog(
    private val context: Context,
    private val repository: AiSkillRepository,
    executor: Executor,
    data: AiDataGeneration,
    post: (() -> Unit) -> Boolean,
    initial: AiContextState,
    private val allowed: () -> Boolean,
    private val manage: () -> Unit,
    private val apply: (List<AiSkill>) -> Boolean,
    private val availableTools: Set<String> = emptySet(),
) : AutoCloseable {
    private val baseCount = initial.characterCount - initial.workspace.skills.sumOf { it.characterCount }
    private val selected = initial.workspace.skills.map { it.id }.toMutableSet()
    private var library = AiSkillLibrary()
    private var loading = true
    private var applying = false
    private var closed = false
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; isSaveEnabled = false
        val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
    private val status = TextView(context)
    private val session = AiLibrarySession(executor, data, post, ::loaded)
    private val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_skill_title)
        .setView(ScrollView(context).apply { addView(rows) }).setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ai_library_apply, null).create()

    fun show() {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { close() }; dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!loading && allowed()) { applying = true; load() }
        }
        load()
    }
    private fun load() {
        loading = true; rows.removeAllViews(); rows.addView(status); status.setText(R.string.ai_library_loading)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        session.run { current -> repository.read(current) }
    }
    private fun loaded(result: Result<AiSkillLibrary>) {
        if (closed || !allowed()) { close(); return }
        loading = false
        result.fold({ value ->
            if (applying) {
                applying = false
                val choices = value.skills.filter { it.id in selected }
                if (library.revision == value.revision && choices.size == selected.size &&
                    choices.all { it.supportedBy(availableTools) } && apply(choices)) { close(); return@fold }
                library = value; selected.clear(); render(); status.setText(R.string.ai_library_expired)
            } else {
                library = value
                selected.retainAll(value.skills.filter { it.supportedBy(availableTools) }.map { it.id }.toSet())
                render()
            }
        }, {
            applying = false; rows.removeAllViews(); rows.addView(status); status.setText(R.string.ai_library_failed)
            rows.addView(button(R.string.ai_library_retry) { load() })
        })
    }
    private fun render() {
        rows.removeAllViews(); rows.addView(status)
        rows.addView(TextView(context).apply { setText(R.string.ai_skill_notice) })
        rows.addView(button(R.string.ai_skill_manage) { if (allowed()) { close(); manage() } })
        if (library.skills.isEmpty()) rows.addView(TextView(context).apply { setText(R.string.ai_skill_empty) })
        library.skills.forEach { skill ->
            val supported = skill.supportedBy(availableTools)
            val details = TextView(context).apply {
                text = skill.instructions; setTextIsSelectable(true)
                visibility = if (skill.id in selected) View.VISIBLE else View.GONE
            }
            rows.addView(CheckBox(context).apply {
                text = skill.name; isChecked = skill.id in selected; isEnabled = supported; isSaveEnabled = false
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += skill.id else selected -= skill.id
                    details.visibility = if (checked) View.VISIBLE else View.GONE
                    updateBudget()
                }
            })
            rows.addView(TextView(context).apply { text = skill.description })
            if (!supported) rows.addView(TextView(context).apply { text = context.getString(R.string.ai_skill_unsupported, skill.requiredTools.joinToString(", ")) })
            rows.addView(details)
        }
        updateBudget()
    }
    private fun updateBudget() {
        val count = baseCount + library.skills.filter { it.id in selected }.sumOf { it.characterCount }
        status.text = context.getString(R.string.ai_library_budget, count, AiReference.MAX_CONTEXT_CHARS)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = count <= AiReference.MAX_CONTEXT_CHARS
    }
    private fun button(title: Int, action: () -> Unit) = MaterialButton(context).apply { setText(title); setOnClickListener { action() } }
    override fun close() {
        if (closed) return
        closed = true; session.close(); dialog.dismiss(); rows.removeAllViews(); status.text = ""; selected.clear(); library = AiSkillLibrary()
    }
}
