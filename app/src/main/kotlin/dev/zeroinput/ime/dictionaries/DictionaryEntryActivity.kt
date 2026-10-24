package dev.zeroinput.ime.dictionaries

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.engine.dictionary.importer.PublicPinyin
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.userdata.DictionaryEdit
import dev.zeroinput.userdata.DictionaryWord
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

class DictionaryEntryActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread("dictionary-editor", 1)
    private val search by lazy { DictionaryEntrySearch(this, graph.publicDictionaries, graph.dictionaryEdits) }
    private lateinit var dictionaryId: String
    private lateinit var query: EditText
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var pending: Future<*>? = null
    private var generation = 0L
    private var offset = 0
    private var changesOnly = false
    private var dialog: AlertDialog? = null
    private var revocationObserver: AutoCloseable? = null
    private var editEpoch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dictionaryId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (!dictionaryId.matches(Regex("[a-z0-9_]{1,64}"))) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(createContent())
    }

    private fun createContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.zero_surface))
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        addView(MaterialToolbar(this@DictionaryEntryActivity).apply {
            setTitle(R.string.dictionary_entries)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = getString(R.string.navigate_up)
            setNavigationOnClickListener { finish() }
            menu.add(R.string.dictionary_add).apply {
                setIcon(R.drawable.ic_add)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { edit(null); true }
            }
        })
        query = dictionaryField(this@DictionaryEntryActivity, R.string.dictionary_search_hint, 256)
        addView(LinearLayout(this@DictionaryEntryActivity).apply {
            isBaselineAligned = false
            addView(query, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(R.string.dictionary_search) { offset = 0; refresh() })
        })
        addView(MaterialSwitch(this@DictionaryEntryActivity).apply {
            setText(R.string.dictionary_changes_only)
            setOnCheckedChangeListener { _, enabled -> changesOnly = enabled; offset = 0; refresh() }
        })
        status = TextView(this@DictionaryEntryActivity)
        addView(status)
        rows = LinearLayout(this@DictionaryEntryActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(this@DictionaryEntryActivity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun refresh() {
        val text = query.text.toString().trim()
        val start = offset
        val onlyChanges = changesOnly
        runTask({ search.search(dictionaryId, text, start, onlyChanges) }) { page ->
            rows.removeAllViews()
            page.entries.forEach { entry ->
                val word = entry.replacement ?: requireNotNull(entry.original)
                rows.addView(MaterialButton(this).apply {
                    this.text = getString(if (entry.replacement == null) R.string.dictionary_deleted_entry else R.string.dictionary_entry,
                        word.text, word.reading, word.weight)
                    isAllCaps = false
                    setOnClickListener { edit(entry) }
                })
            }
            if (start > 0) rows.addView(button(R.string.dictionary_previous) { offset = (start - DictionaryEntrySearch.PAGE_SIZE).coerceAtLeast(0); refresh() })
            if (page.hasMore) rows.addView(button(R.string.dictionary_next) { offset = start + DictionaryEntrySearch.PAGE_SIZE; refresh() })
            rows.addView(TextView(this).apply { setText(R.string.dictionary_edit_notice) })
            status.text = if (page.entries.isEmpty()) getString(R.string.dictionary_no_entries)
                else getString(R.string.dictionary_result_count, start + 1, start + page.entries.size)
        }
    }

    private fun edit(entry: DictionaryEdit?) {
        // Bind to the displayed data, not the generation at Save: reset notifications
        // may still be queued on the UI thread when an old form is submitted.
        val expected = editEpoch
        val initial = entry?.replacement ?: entry?.original
        val text = dictionaryField(this, R.string.dictionary_word, 256, initial?.text.orEmpty())
        val reading = dictionaryField(this, R.string.dictionary_reading, 1024, initial?.reading.orEmpty())
        val weight = dictionaryField(this, R.string.dictionary_weight, 10, (initial?.weight ?: 1).toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text); addView(reading); addView(weight)
            if (entry != null) addView(button(R.string.dictionary_undo_entry) {
                mutate(expected) { graph.dictionaryEdits.undo(dictionaryId, entry.key, expected) }
            })
        }
        val editor = MaterialAlertDialogBuilder(this).setTitle(R.string.dictionary_entries)
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
        if (entry != null) editor.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(entry, expected) }
        dialog = editor.create().also { shown ->
            shown.setOnDismissListener { text.text.clear(); reading.text.clear(); weight.text.clear() }
            shown.setOnShowListener {
                shown.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val replacement = runCatching {
                        val code = PublicPinyin.normalize(reading.text.toString())
                        require(code.isNotBlank())
                        DictionaryWord(text.text.toString().trim(), code, weight.text.toString().toInt())
                    }.getOrNull()
                    if (replacement == null) { weight.error = getString(R.string.dictionary_invalid_entry); return@setOnClickListener }
                    val value = DictionaryEdit(dictionaryId, entry?.original, replacement)
                    mutate(expected) {
                        if (entry != null && entry.original == null && entry.key != value.key) {
                            graph.dictionaryEdits.replaceAddition(entry, value, expected)
                        } else graph.dictionaryEdits.save(value, expected)
                    }
                }
            }
            shown.show()
        }
    }

    private fun confirmDelete(entry: DictionaryEdit, expected: Long) {
        dialog = MaterialAlertDialogBuilder(this).setMessage(if (entry.original == null)
            R.string.dictionary_delete_added_confirm else R.string.dictionary_delete_entry_confirm)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ ->
                mutate(expected) {
                    if (entry.original == null) graph.dictionaryEdits.undo(dictionaryId, entry.key, expected)
                    else graph.dictionaryEdits.save(entry.copy(replacement = null), expected)
                }
            }.show().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun mutate(expected: Long, operation: () -> Unit) {
        val submittedDialog = dialog
        runTask({
            if (expected != graph.dictionaryEdits.generation()) throw java.util.concurrent.CancellationException()
            operation()
        }) {
            submittedDialog?.dismiss()
            if (dialog === submittedDialog) dialog = null
            refresh()
        }
    }

    private fun <T> runTask(work: () -> T, complete: (T) -> Unit) {
        cancel()
        val token = generation
        status.setText(R.string.public_dictionary_working)
        try {
            pending = worker.submit {
                val result = runCatching(work)
                runOnUiThread {
                    if (token == generation && !isDestroyed) {
                        pending = null
                        result.fold(complete) { status.setText(R.string.public_dictionary_failed) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) { status.setText(R.string.public_dictionary_failed) }
    }

    private fun button(label: Int, action: () -> Unit) = MaterialButton(this).apply { setText(label); setOnClickListener { action() } }
    private fun cancel() { generation++; pending?.cancel(true); pending = null; BoundedExecutors.purge(worker) }
    override fun onStart() {
        super.onStart()
        editEpoch = graph.dictionaryEdits.generation()
        revocationObserver = graph.addPersonalizationListener {
            runOnUiThread {
                if (revocationObserver != null && editEpoch != graph.dictionaryEdits.generation()) {
                    editEpoch = graph.dictionaryEdits.generation()
                    dialog?.dismiss(); dialog = null; rows.removeAllViews(); query.text.clear(); offset = 0
                    refresh()
                }
            }
        }
        if (::query.isInitialized) refresh()
    }
    override fun onStop() {
        revocationObserver?.close(); revocationObserver = null
        cancel(); dialog?.dismiss(); dialog = null
        if (::rows.isInitialized) { rows.removeAllViews(); query.text.clear(); offset = 0 }
        super.onStop()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    companion object { const val EXTRA_ID = "dictionary_id" }
}
