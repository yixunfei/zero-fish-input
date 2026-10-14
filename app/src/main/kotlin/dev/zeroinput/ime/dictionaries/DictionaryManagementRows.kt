package dev.zeroinput.ime.dictionaries

import android.content.Intent
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.AppGraph
import dev.zeroinput.ime.R
import dev.zeroinput.languagepack.BuiltInDictionary
import dev.zeroinput.languagepack.InstalledPublicDictionary

internal class DictionaryManagementRows(
    private val activity: AppCompatActivity,
    private val graph: AppGraph,
    private val command: (String, () -> Unit) -> Unit,
    private val run: (() -> Unit, () -> Unit) -> Unit,
    private val refresh: () -> Unit,
    private val download: (String?, InstalledPublicDictionary?) -> Unit,
) {
    private var dialog: AlertDialog? = null

    fun builtIn(item: BuiltInDictionary, names: Map<String, String>) {
        val title = names[item.id] ?: builtInTitle(item.table)
        val state = activity.getString(when {
            item.required -> R.string.dictionary_required
            item.deleted -> R.string.dictionary_deleted
            item.enabled -> R.string.dictionary_enabled
            else -> R.string.dictionary_disabled
        })
        command(activity.getString(R.string.dictionary_row, title, state)) { actions(item.id, title, item, null) }
    }

    fun downloaded(item: InstalledPublicDictionary, names: Map<String, String>) {
        val title = names[item.id] ?: item.title
        val state = activity.getString(if (item.enabled) R.string.dictionary_enabled else R.string.dictionary_disabled)
        command(activity.getString(R.string.dictionary_row, title, state)) { actions(item.id, title, null, item) }
    }

    private fun actions(id: String, title: String, builtin: BuiltInDictionary?, remote: InstalledPublicDictionary?) {
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (builtin != null) form.addView(android.widget.TextView(activity).apply {
            text = activity.getString(R.string.dictionary_builtin_details,
                activity.getString(R.string.public_dictionary_wanxiang), builtin.version,
                (builtin.replacement?.bytes ?: builtin.bytes) / 1024)
        })
        if (remote != null) form.addView(android.widget.TextView(activity).apply {
            text = activity.getString(R.string.public_dictionary_item, title, remote.source, remote.entries,
                remote.bytes / 1024, remote.version.take(12))
        })
        form.addView(MaterialSwitch(activity).apply {
            setText(R.string.public_dictionary_enable)
            isChecked = builtin?.enabled ?: requireNotNull(remote).enabled
            isEnabled = builtin?.required != true && builtin?.deleted != true
            setOnCheckedChangeListener { _, enabled -> operation {
                if (builtin != null) graph.builtInDictionaries.setEnabled(builtin.table, enabled)
                else graph.publicDictionaries.setEnabled(id, enabled)
            } }
        })
        fun action(label: Int, work: () -> Unit) { form.addView(MaterialButton(activity).apply { setText(label); setOnClickListener { close(); work() } }) }
        action(R.string.dictionary_entries) {
            activity.startActivity(Intent(activity, DictionaryEntryActivity::class.java).putExtra(DictionaryEntryActivity.EXTRA_ID, id))
        }
        action(R.string.dictionary_rename) { rename(id, title) }
        action(R.string.dictionary_redownload) { download(builtin?.table, remote) }
        if (builtin != null) action(R.string.dictionary_offline_restore) { operation { graph.builtInDictionaries.restore(builtin.table) } }
        action(R.string.dictionary_restore_original) {
            confirm(R.string.dictionary_restore_original_confirm) { operation {
                if (builtin != null) graph.builtInDictionaries.restore(builtin.table)
                graph.dictionaryEdits.reset(id)
            } }
        }
        if (builtin?.required != true) action(R.string.public_dictionary_remove) {
            confirm(R.string.dictionary_remove_notice) { operation {
                if (builtin != null) graph.builtInDictionaries.remove(builtin.table) else graph.publicDictionaries.remove(id)
            } }
        }
        dialog = MaterialAlertDialogBuilder(activity).setTitle(title)
            .setView(android.widget.ScrollView(activity).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null).show().secure()
    }

    private fun rename(id: String, title: String) {
        val generation = graph.dictionaryEdits.generation()
        val field = dictionaryField(activity, R.string.dictionary_name, 160, title)
        val shown = MaterialAlertDialogBuilder(activity).setTitle(R.string.dictionary_rename).setView(field)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        shown.setOnDismissListener { field.text.clear() }
        shown.setOnShowListener {
            shown.secure()
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isBlank()) { field.error = activity.getString(R.string.dictionary_invalid_entry); return@setOnClickListener }
                operation { graph.dictionaryEdits.rename(id, name, generation) }
            }
        }
        dialog = shown
        shown.show()
    }

    private fun confirm(message: Int, action: () -> Unit) {
        dialog = MaterialAlertDialogBuilder(activity).setMessage(message).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }.show().secure()
    }
    private fun operation(work: () -> Unit) {
        val submittedDialog = dialog
        run(work) {
            submittedDialog?.dismiss()
            if (dialog === submittedDialog) dialog = null
            refresh()
        }
    }
    fun close() { dialog?.dismiss(); dialog = null }
    private fun AlertDialog.secure(): AlertDialog = apply { window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    private fun builtInTitle(table: String): String {
        val index = graphTableOrder.indexOf(table)
        return activity.resources.getStringArray(R.array.dictionary_builtin_names).getOrNull(index) ?: table
    }
    private val graphTableOrder = listOf("zi", "jichu", "lianxiang", "cuoyin", "duoyin", "shici", "diming", "yixue",
        "huaxue", "yaopin", "mingren", "yiren", "wuzhong", "renming", "taifeng", "fangyan")
}
