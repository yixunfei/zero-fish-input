package dev.zeroinput.ime.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.userdata.UserTerm
import dev.zeroinput.userdata.UserDictionaryException
import dev.zeroinput.userdata.UserDictionaryFailure
import java.util.concurrent.RejectedExecutionException

class UserDictionaryActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread(
        name = "zeroinput-user-dictionary",
        queueCapacity = 8,
    )
    private val adapter = UserDictionaryAdapter(onEdit = ::showEditDialog, onDelete = ::confirmDelete)
    private var phraseDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var toolbar: MaterialToolbar
    private var busy = false
    private var operationGeneration = 0L
    private lateinit var searchField: EditText
    private val searchRefresh = Runnable { refresh() }
    private var searchRevision = 0L
    private var searchPending = false
    private var pageOffset = 0
    private var hasMore = false
    private lateinit var previousPage: com.google.android.material.button.MaterialButton
    private lateinit var nextPage: com.google.android.material.button.MaterialButton

    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runOperation({ exportTo(uri) }) { showMessage(R.string.user_dictionary_exported) }
    }

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runOperation(
            operation = {
                val input = contentResolver.openInputStream(uri) ?: error("Unable to open import")
                val bytes = input.use { it.readBoundedBytes(MAX_IMPORT_BYTES) }
                try {
                    graph.userLexicon.importEncrypted(bytes, replace = false)
                } finally {
                    bytes.fill(0)
                }
            },
            onSuccess = {
                Toast.makeText(this, resources.getQuantityString(R.plurals.user_dictionary_imported, it, it), Toast.LENGTH_SHORT).show()
                onDictionaryChanged()
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(createContent())
        refresh()
    }

    override fun onDestroy() {
        operationGeneration++
        phraseDialog?.dismiss()
        phraseDialog = null
        searchField.removeCallbacks(searchRefresh)
        adapter.submit(emptyList())
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun createContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        toolbar = createToolbar()
        addView(toolbar)
        searchField = dictionaryField(R.string.user_dictionary_search, maxLength = 64, maxLines = 1).apply {
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchRevision++
                    pageOffset = 0
                    adapter.submit(emptyList())
                    removeCallbacks(searchRefresh)
                    postDelayed(searchRefresh, SEARCH_DELAY_MS)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(searchField)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        addView(RecyclerView(this@UserDictionaryActivity).apply {
            layoutManager = LinearLayoutManager(this@UserDictionaryActivity)
            adapter = this@UserDictionaryActivity.adapter
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        addView(createPaging())
    }

    private fun createPaging() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        previousPage = pageButton(R.string.user_dictionary_previous, -SEARCH_LIMIT)
        nextPage = pageButton(R.string.user_dictionary_next, SEARCH_LIMIT)
        addView(previousPage)
        addView(nextPage)
    }

    private fun pageButton(label: Int, step: Int) = com.google.android.material.button.MaterialButton(this).apply {
        setText(label)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        minHeight = dp(48)
        setOnClickListener {
            if (!busy) {
                pageOffset = (pageOffset + step).coerceAtLeast(0)
                searchRevision++
                refresh()
            }
        }
    }

    private fun createToolbar() = MaterialToolbar(this).apply {
        title = getString(R.string.setting_user_phrases)
        setNavigationIcon(R.drawable.ic_arrow_back)
        navigationContentDescription = getString(R.string.navigate_up)
        setNavigationOnClickListener { finish() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        menu.add(0, MENU_ADD, 0, R.string.user_dictionary_add).apply {
            setIcon(R.drawable.ic_add)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(0, MENU_IMPORT, 1, R.string.user_dictionary_import)
        menu.add(0, MENU_EXPORT, 2, R.string.user_dictionary_export)
        menu.add(0, MENU_THRESHOLD, 3, R.string.user_dictionary_threshold)
        setOnMenuItemClickListener {
            if (!busy) when (it.itemId) {
                MENU_ADD -> showAddDialog()
                MENU_IMPORT -> confirmImport()
                MENU_EXPORT -> confirmExport()
                MENU_THRESHOLD -> showThresholdDialog()
            }
            true
        }
    }

    private fun confirmExport() {
        UserDictionaryTransferDialogs.export(this) { exporter.launch("zeroinput-phrases.zlex") }.show()
    }

    private fun confirmImport() {
        UserDictionaryTransferDialogs.import(this) { importer.launch(arrayOf("application/octet-stream", "application/x-zeroinput-lexicon")) }.show()
    }

    private fun exportTo(uri: Uri) {
        val bytes = graph.userLexicon.exportEncrypted()
        try {
            val output = contentResolver.openOutputStream(uri, "wt") ?: error("Unable to open export")
            output.use { it.write(bytes) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun showAddDialog() = showPhraseEditor(null)

    private fun showEditDialog(term: UserTerm) = showPhraseEditor(term)

    private fun showPhraseEditor(term: UserTerm?) {
        if (busy) return
        phraseDialog = UserPhraseEditor.show(this, term, { busy }) { edit, dialog ->
            graph.personalization.invalidatePendingWrites()
            runOperation({
                if (term == null) graph.userLexicon.addPhrase(edit.shortcut, edit.value, edit.language, edit.weight)
                else graph.userLexicon.updatePhrase(term.id, edit.shortcut, edit.value, edit.language, edit.weight)
            }) {
                dialog.dismiss()
                onDictionaryChanged()
            }
        }
    }
    private fun showThresholdDialog() {
        val field = dictionaryField(R.string.user_dictionary_threshold, 3, 1).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(String.format(java.util.Locale.ROOT, "%d", graph.settings.personalAssociationPromotionThreshold))
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.user_dictionary_threshold)
            .setMessage(getString(R.string.user_dictionary_threshold_message, graph.settings.personalAssociationPromotionThreshold))
            .setView(field).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().toIntOrNull()
                if (value == null || value !in 1..100) { showMessage(R.string.user_dictionary_threshold_invalid); return@setOnClickListener }
                graph.settings.personalAssociationPromotionThreshold = value
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun dictionaryField(hint: Int, maxLength: Int, maxLines: Int) =
        UserPhraseEditor.field(this, hint, maxLength, maxLines)

    private fun confirmDelete(term: UserTerm) {
        if (busy) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.user_dictionary_delete)
            .setMessage(term.value)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                graph.personalization.invalidatePendingWrites()
                runOperation({ graph.userLexicon.remove(term.id) }) { onDictionaryChanged() }
            }
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun refresh() {
        if (busy) { searchPending = true; return }
        searchPending = false
        val prefix = searchField.text.toString()
        val revision = searchRevision
        val offset = pageOffset
        runOperation({ graph.userLexicon.searchPage(prefix, offset = offset, limit = SEARCH_LIMIT) }) {
            if (revision == searchRevision) {
                adapter.submit(it.items)
                hasMore = it.hasMore
                updatePaging()
            }
        }
    }

    private fun onDictionaryChanged() {
        graph.personalization.invalidatePendingWrites()
        refresh()
    }

    private fun <T> runOperation(operation: () -> T, onSuccess: (T) -> Unit) {
        if (isFinishing || isDestroyed) return
        setBusy(true)
        val generation = ++operationGeneration
        try {
            worker.execute {
                val result = runCatching(operation)
                runOnUiThread {
                    if (generation != operationGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    result.onSuccess(onSuccess).onFailure(::showFailure)
                    if (searchPending && !busy) refresh()
                }
            }
        } catch (_: RejectedExecutionException) {
            setBusy(false)
            showMessage(R.string.operation_failed)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        toolbar.menu.forEach { it.isEnabled = !value }
        updatePaging()
    }

    private fun updatePaging() {
        previousPage.isEnabled = !busy && pageOffset > 0
        nextPage.isEnabled = !busy && hasMore
    }

    private fun showFailure(error: Throwable) {
        val resource = when ((error as? UserDictionaryException)?.failure) {
            UserDictionaryFailure.CAPACITY_EXCEEDED -> R.string.user_dictionary_full
            UserDictionaryFailure.IMPORT_TOO_LARGE -> R.string.user_dictionary_too_large
            UserDictionaryFailure.INVALID_FORMAT -> R.string.user_dictionary_invalid
            UserDictionaryFailure.WRITE_FAILED -> R.string.user_dictionary_write_failed
            null -> R.string.operation_failed
        }
        showMessage(resource)
    }

    private fun showMessage(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        const val MENU_ADD = 1
        const val MENU_IMPORT = 2
        const val MENU_EXPORT = 3
        const val MENU_THRESHOLD = 4
        const val SEARCH_LIMIT = 50
        const val SEARCH_DELAY_MS = 220L
    }
}
