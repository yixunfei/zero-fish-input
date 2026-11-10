package dev.zeroinput.ime.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Single-use picker handoff. No private content or URI is stored in an Intent or saved state. */
internal class AiTaskDocumentPicker(private val activity: AppCompatActivity, private val worker: Executor,
    private val data: AiDataGeneration, private val main: Handler, private val visible: () -> Boolean,
    private val expired: () -> Unit) : AutoCloseable {
    private class Pending(val epoch: Long, val data: Long, val deadline: Long, val output: AiFileOutput?,
        val selected: (Uri, String) -> Unit, val saved: (Uri?) -> Unit) {
        var returned = false
        var uri: Uri? = null
    }
    private val epoch = AtomicLong()
    private var pending: Pending? = null
    private var signal: CancellationSignal? = null
    private var review: AlertDialog? = null
    private var openTicket: Long? = null
    private var createTicket: Long? = null
    var external = false
        private set
    private val timeout = Runnable { close(); expired() }
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        val ticket = openTicket; openTicket = null; returned(ticket, it)
    }
    private val create = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ticket = createTicket; createTicket = null
        returned(ticket, if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }

    fun select(completed: (Uri, String) -> Unit) {
        if (!visible() || pending != null || openTicket != null || createTicket != null) return
        begin(null, completed, {})
        openTicket = pending?.epoch
        try { open.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/json", "text/csv")) }
        catch (_: Exception) { openTicket = null; close(); expired() }
    }
    fun destination(output: AiFileOutput, completed: (Uri?) -> Unit): AutoCloseable {
        check(visible() && pending == null && openTicket == null && createTicket == null)
        begin(output, { _, _ -> }, completed)
        createTicket = pending?.epoch
        try {
            create.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(output.mime).putExtra(Intent.EXTRA_TITLE, output.name))
        } catch (_: Exception) { createTicket = null; close(); expired() }
        val ticket = pending?.epoch
        return AutoCloseable { if (pending?.epoch == ticket) close() }
    }
    private fun begin(output: AiFileOutput?, selected: (Uri, String) -> Unit, saved: (Uri?) -> Unit) {
        pending = Pending(epoch.incrementAndGet(), data.current(), SystemClock.elapsedRealtime() + 120_000, output, selected, saved)
        external = true; main.postDelayed(timeout, 120_000)
    }
    private fun current(value: Pending) = pending === value && epoch.get() == value.epoch && data.isCurrent(value.data) &&
        SystemClock.elapsedRealtime() <= value.deadline
    private fun returned(ticket: Long?, uri: Uri?) {
        val value = pending ?: return
        if (value.epoch != ticket) return
        external = false
        if (!current(value)) { close(); expired(); return }
        value.uri = uri?.takeIf { it.scheme == "content" }; value.returned = true
        resume()
    }
    fun resume() {
        val value = pending ?: return
        if (!visible() || !value.returned) return
        value.returned = false
        val uri = value.uri
        if (uri == null) { close(); value.saved(null); return }
        val cancellation = CancellationSignal().also { signal = it }
        val resolver = activity.applicationContext.contentResolver
        val weak = WeakReference(this); val handler = main
        val ticket = value.epoch; val dataToken = value.data; val deadline = value.deadline
        val epochSource = epoch; val dataSource = data
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, minOf(30_000, (value.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
        try {
            worker.execute {
                val label = runCatching {
                    cancellation.throwIfCanceled()
                    check(epochSource.get() == ticket && dataSource.isCurrent(dataToken) && SystemClock.elapsedRealtime() <= deadline)
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString) else null
                    }?.takeIf { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }
                }.getOrNull()
                handler.post { weak.get()?.resolved(ticket, uri, label) }
            }
        } catch (_: RejectedExecutionException) { close(); expired() }
    }
    private fun resolved(ticket: Long, uri: Uri, label: String?) {
        val value = pending?.takeIf { it.epoch == ticket } ?: return
        if (!current(value) || !visible()) { close(); expired(); return }
        signal = null
        main.removeCallbacks(timeout); main.postDelayed(timeout, (value.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        val name = label ?: activity.getString(R.string.ai_tool_selected_document)
        val output = value.output
        if (output == null) { close(); value.selected(uri, name); return }
        val text = TextView(activity).apply {
            isSaveEnabled = false
            text = activity.getString(R.string.ai_tool_save_preview, name, output.content)
            setPadding(24, 16, 24, 16)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        review = MaterialAlertDialogBuilder(activity).setTitle(R.string.ai_tool_confirm_write)
            .setView(ScrollView(activity).apply { addView(text) })
            .setNegativeButton(R.string.cancel) { _, _ -> if (current(value)) { close(); value.saved(null) } }
            .setPositiveButton(R.string.ai_tool_write) { _, _ ->
                if (visible() && current(value)) { close(); value.saved(uri) } else { close(); expired() }
            }.create().also { dialog ->
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnCancelListener { if (current(value)) { close(); value.saved(null) } }
                dialog.setOnDismissListener { text.text = "" }
                dialog.show()
            }
    }
    override fun close() {
        epoch.incrementAndGet(); pending = null; external = false
        main.removeCallbacks(timeout); signal?.cancel(); signal = null
        review?.dismiss(); review = null
    }
}
