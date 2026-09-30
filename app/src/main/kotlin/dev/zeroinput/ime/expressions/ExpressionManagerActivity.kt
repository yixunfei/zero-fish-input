package dev.zeroinput.ime.expressions

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import dev.zeroinput.userdata.ExpressionException
import dev.zeroinput.userdata.ExpressionFailure
import dev.zeroinput.userdata.PersonalExpression
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

open class ExpressionManagerActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    protected open val repository: dev.zeroinput.userdata.PersonalExpressionRepository get() = graph.expressions
    private val worker = BoundedExecutors.singleThread("zeroinput-expressions", 2)
    private val generation = AtomicLong()
    private var task: Future<*>? = null
    private val adapter = ExpressionManagerAdapter(::edit, ::confirmDelete)
    private var form: ExpressionEditorDialog? = null
    private var confirmation: AlertDialog? = null
    private var observer: AutoCloseable? = null
    private var catalogPreparation: java.io.Closeable? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var status: TextView
    private var busy = false
    private var foreground = false
    private var requestedId: String? = null
    private var pendingImportUri: Uri? = null

    private val exporter = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runOperation({
            val bytes = repository.exportEncrypted()
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("Unable to open export")
            } finally {
                bytes.fill(0)
            }
            Unit
        }) { Toast.makeText(this, R.string.expression_exported, Toast.LENGTH_SHORT).show() }
    }

    private val importer = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImportUri = uri
        drainPendingImport()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        catalogPreparation = dev.zeroinput.ime.ui.EmojiCatalog.prepare(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestedId = intent.getStringExtra(EDIT_ID)?.takeIf { it.length == 36 }
        intent.removeExtra(EDIT_ID)
        setContentView(content())
        observer = graph.observeExpressions {
            runOnUiThread {
                if (foreground) {
                    generation.incrementAndGet()
                    task?.cancel(false)
                    BoundedExecutors.purge(worker)
                    setBusy(false)
                    adapter.submit(emptyList())
                    form?.dialog?.dismiss()
                    form = null
                    confirmation?.dismiss()
                    refresh()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refresh()
        drainPendingImport()
    }

    override fun onPause() {
        foreground = false
        generation.incrementAndGet()
        task?.cancel(false)
        BoundedExecutors.purge(worker)
        form?.dialog?.dismiss()
        form = null
        confirmation?.dismiss()
        confirmation = null
        adapter.submit(emptyList())
        requestedId = null
        status.text = ""
        busy = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun onDestroy() {
        catalogPreparation?.close()
        observer?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun content() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        toolbar = MaterialToolbar(this@ExpressionManagerActivity).apply {
            setTitle(R.string.expression_manager_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = getString(R.string.navigate_up)
            setNavigationOnClickListener { finish() }
            menu.add(0, 1, 0, R.string.expression_add_custom).apply {
                setIcon(R.drawable.ic_add)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(0, MENU_IMPORT, 1, R.string.expression_import)
            menu.add(0, MENU_EXPORT, 2, R.string.expression_export)
            setOnMenuItemClickListener {
                if (!busy) when (it.itemId) {
                    1 -> edit(null)
                    MENU_IMPORT -> importer.launch(arrayOf("application/octet-stream", "application/x-zeroinput-expressions"))
                    MENU_EXPORT -> confirmExport()
                }
                true
            }
        }
        addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        status = TextView(this@ExpressionManagerActivity).apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        addView(status)
        addView(RecyclerView(this@ExpressionManagerActivity).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ExpressionManagerActivity.adapter
            itemAnimator = null
            isSaveEnabled = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun refresh() {
        if (!foreground || busy) return
        runOperation({ current -> repository.snapshot(isCurrent = current).data.custom }) { entries ->
            adapter.submit(entries)
            status.text = if (entries.isEmpty()) getString(R.string.expression_custom_empty)
                else resources.getQuantityString(R.plurals.expression_custom_count, entries.size, entries.size)
            val id = requestedId
            requestedId = null
            if (id != null) entries.firstOrNull { it.id == id }?.let(::edit)
        }
    }

    private fun drainPendingImport() {
        val uri = pendingImportUri ?: return
        if (!foreground || busy) return
        pendingImportUri = null
        runOperation({ current ->
            val bytes = contentResolver.openInputStream(uri)?.use { input -> input.readBoundedBytes(EXPRESSION_IMPORT_BYTES) }
                ?: error("Unable to open import")
            try {
                repository.importEncrypted(bytes, repository.generation(), current)
            } finally {
                bytes.fill(0)
            }
        }) {
            graph.notifyExpressionsChanged()
            Toast.makeText(this, R.string.expression_imported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun edit(entry: PersonalExpression?) {
        if (!foreground || busy) return
        val deletion = repository.generation()
        form?.dialog?.dismiss()
        form = ExpressionEditorDialog(this, entry) { draft ->
            runOperation({ current ->
                repository.save(entry?.id, draft.value, draft.name, draft.keywords, draft.group, deletion, current)
            }) {
                form?.dialog?.dismiss()
                form = null
                graph.notifyExpressionsChanged()
            }
        }.also { it.dialog.show() }
    }

    private fun confirmDelete(entry: PersonalExpression) {
        if (!foreground || busy) return
        val deletion = repository.generation()
        confirmation = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.expression_delete_custom)
            .setMessage(R.string.expression_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runOperation({ current -> repository.remove(entry.id, deletion, current) }) { graph.notifyExpressionsChanged() }
            }.show().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun confirmExport() {
        if (!foreground || busy) return
        confirmation?.dismiss()
        confirmation = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.expression_export)
            .setMessage(R.string.expression_export_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.expression_export) { _, _ ->
                exporter.launch("zeroinput-expressions.zexp")
            }
            .show()
            .also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun <T> runOperation(operation: (() -> Boolean) -> T, success: (T) -> Unit) {
        if (!foreground || busy) return
        val expected = generation.incrementAndGet()
        setBusy(true)
        val current = { generation.get() == expected }
        try {
            task = worker.submit {
                val result = runCatching { operation(current) }
                runOnUiThread {
                    if (!current() || !foreground || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    result.onSuccess(success).onFailure { error ->
                        val message = when ((error as? ExpressionException)?.failure) {
                            ExpressionFailure.INVALID -> R.string.expression_invalid
                            ExpressionFailure.DUPLICATE -> R.string.expression_duplicate
                            ExpressionFailure.CAPACITY -> R.string.expression_capacity
                            else -> R.string.expression_operation_failed
                        }
                        status.setText(message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    drainPendingImport()
                }
            }
        } catch (_: RejectedExecutionException) {
            setBusy(false)
            status.setText(R.string.expression_operation_failed)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        toolbar.menu.forEach { it.isEnabled = !value }
        form?.setBusy(value)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun java.io.InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) error("Import is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val EDIT_ID = "expression_edit_id"
        private const val MENU_IMPORT = 2
        private const val MENU_EXPORT = 3
        private const val EXPRESSION_IMPORT_BYTES = 512 * 1024 + 64
    }
}
