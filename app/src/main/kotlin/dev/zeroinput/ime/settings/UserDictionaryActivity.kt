package dev.zeroinput.ime.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.engine.api.InputLanguage
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
    private val adapter = UserDictionaryAdapter(::confirmDelete)
    private lateinit var toolbar: MaterialToolbar
    private var busy = false
    private var operationGeneration = 0L

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
        adapter.submit(emptyList())
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun createContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        toolbar = createToolbar()
        addView(toolbar)
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
        setOnMenuItemClickListener {
            if (!busy) when (it.itemId) {
                MENU_ADD -> showAddDialog()
                MENU_IMPORT -> confirmImport()
                MENU_EXPORT -> confirmExport()
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

    private fun showAddDialog() {
        val shortcut = dictionaryField(R.string.user_dictionary_shortcut, maxLength = 64, maxLines = 1)
        val value = dictionaryField(R.string.user_dictionary_value, maxLength = 128, maxLines = 2)
        val chineseId = View.generateViewId()
        val englishId = View.generateViewId()
        val languages = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@UserDictionaryActivity).apply {
                id = chineseId
                text = getString(R.string.language_chinese)
                isChecked = true
            })
            addView(RadioButton(this@UserDictionaryActivity).apply {
                id = englishId
                text = getString(R.string.language_english)
            })
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
            addView(shortcut)
            addView(value)
            addView(languages)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.user_dictionary_add)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnDismissListener {
            shortcut.text.clear()
            value.text.clear()
        }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy) return@setOnClickListener
                if (shortcut.text.isBlank() || value.text.isBlank()) {
                    showMessage(R.string.user_dictionary_required)
                    return@setOnClickListener
                }
                val language = if (languages.checkedRadioButtonId == englishId) {
                    InputLanguage.ENGLISH
                } else {
                    InputLanguage.CHINESE
                }
                val shortcutText = shortcut.text.toString()
                val phraseText = value.text.toString()
                runOperation({ graph.userLexicon.addPhrase(shortcutText, phraseText, language) }) {
                    dialog.dismiss()
                    onDictionaryChanged()
                }
            }
        }
        dialog.show()
    }

    private fun dictionaryField(hint: Int, maxLength: Int, maxLines: Int) = EditText(this).apply {
        setHint(hint)
        this.maxLines = maxLines
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        isSaveEnabled = false
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        }
    }

    private fun confirmDelete(term: UserTerm) {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.user_dictionary_delete)
            .setMessage(term.value)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runOperation({ graph.userLexicon.remove(term.id) }) { onDictionaryChanged() }
            }
            .show().window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun refresh() {
        runOperation({ graph.userLexicon.list() }) { adapter.submit(it) }
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
    }
}
