package dev.zeroinput.ime.ai

import android.os.Handler
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiConnectionRepository
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Private connection CRUD and explicit metadata review; nothing connects on navigation or Save. */
internal class AiConnectionsDialog(private val activity: AppCompatActivity, private val repository: AiConnectionRepository,
    private val persistence: Executor, private val worker: Executor, private val data: AiDataGeneration,
    private val revisions: AiDataGeneration, private val main: Handler, private val allowed: () -> Boolean,
    private val networkAllowed: () -> Boolean, private val changing: () -> Unit,
    private val apply: (AiRemoteTools, Set<String>) -> Boolean,
    private val transport: AiRemoteTransport = AiRemoteHttpsTransport()) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val ownerData = data.current()
    private var library = AiConnectionLibrary()
    private var busy = true
    private var cancel: AiRemoteCancellation? = null
    private var bundle: AiRemoteTools? = null
    private var bundleRevision = -1L
    private var editor: AlertDialog? = null
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; privateView(this) }
    private val status = TextView(activity)
    private val session = AiLibrarySession<AiConnectionLibrary>(persistence, data, { main.post(it) }, ::loaded)
    private val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.ai_remote_title)
        .setView(ScrollView(activity).apply { addView(rows); privateView(this) })
        .setNegativeButton(R.string.cancel, null).create()
    private val timeout = Runnable { cancel?.cancel(); cancel = null; bundle?.close(); bundle = null; busy = false; render(); status.setText(R.string.ai_remote_failed) }

    fun show() {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { close() }; dialog.show()
        session.run { repository.read(it) }
    }
    private fun live() = !closed.get() && data.isCurrent(ownerData) && allowed()
    private fun loaded(result: Result<AiConnectionLibrary>) {
        if (!live()) return
        busy = false
        result.fold({ library = it; render() }, { rows.removeAllViews(); rows.addView(status); status.setText(R.string.ai_library_failed) })
    }
    private fun render() {
        rows.removeAllViews()
        rows.addView(TextView(activity).apply { setText(R.string.ai_remote_notice) }); rows.addView(status)
        status.text = ""
        if (library.connections.size < 8) {
            rows.addView(button(R.string.ai_remote_add_mcp) { edit(null, AiConnectionKind.MCP) })
            rows.addView(button(R.string.ai_remote_add_search) { edit(null, AiConnectionKind.BRAVE) })
        }
        library.connections.forEach { connection ->
            rows.addView(TextView(activity).apply { text = connection.name; textSize = 18f })
            rows.addView(TextView(activity).apply { text = connection.endpoint })
            rows.addView(button(if (connection.kind == AiConnectionKind.MCP) R.string.ai_remote_connect else R.string.ai_remote_select_search) { connect(connection) })
            rows.addView(button(R.string.ai_library_edit) { edit(connection, connection.kind) })
            rows.addView(button(R.string.ai_library_delete) { delete(connection) })
        }
    }
    private fun edit(existing: AiConnection?, kind: AiConnectionKind) {
        if (!live() || busy) return
        changing(); bundle?.close(); bundle = null
        val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; privateView(this); setPadding(24, 8, 24, 8) }
        val name = field(R.string.ai_remote_name, 64, existing?.name.orEmpty())
        val endpoint = field(R.string.ai_remote_endpoint, 2_048, existing?.endpoint ?: if (kind == AiConnectionKind.BRAVE) AiConnection.BRAVE_ENDPOINT else "")
        endpoint.isEnabled = kind == AiConnectionKind.MCP
        val token = field(R.string.ai_remote_token, 4_096, existing?.token.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val error = TextView(activity)
        listOf(name, endpoint, token, error).forEach(fields::addView)
        editor = MaterialAlertDialogBuilder(activity).setTitle(R.string.ai_remote_title).setView(ScrollView(activity).apply { addView(fields) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_library_save, null).create().also { edit ->
                edit.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); edit.show()
                edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!live() || busy) return@setOnClickListener
                    val value = runCatching {
                        AiConnection(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(), kind,
                            endpoint.text.toString().trim(), token.text.toString().trim()).also { AiRemotePolicy.endpoint(it.endpoint) }
                    }.getOrNull()
                    if (value == null) error.setText(R.string.ai_remote_invalid) else { save(library.connections.filterNot { it.id == value.id } + value); edit.dismiss() }
                }
                edit.setOnDismissListener { name.text?.clear(); endpoint.text?.clear(); token.text?.clear() }
            }
    }
    private fun delete(connection: AiConnection) {
        if (!live() || busy) return
        editor = MaterialAlertDialogBuilder(activity).setTitle(R.string.ai_remote_delete)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_library_delete) { _, _ ->
                if (live() && !busy) save(library.connections.filterNot { it.id == connection.id })
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
    }
    private fun save(connections: List<AiConnection>) {
        changing(); revisions.invalidate(); bundle?.close(); bundle = null; busy = true; render()
        val expected = library.revision; val value = AiConnectionLibrary(connections = connections)
        session.run { repository.write(expected, value, it) }
    }
    private fun connect(connection: AiConnection) {
        if (!live() || busy || !networkAllowed()) { status.setText(R.string.ai_remote_network_off); return }
        changing(); bundle?.close(); bundle = null
        busy = true; render(); status.setText(R.string.ai_remote_loading)
        val currentData = data; val dataToken = ownerData; val closedFlag = closed
        val revision = revisions.current(); val revisionSource = revisions
        bundleRevision = revision
        val cancellation = AiRemoteCancellation { !closedFlag.get() && currentData.isCurrent(dataToken) && revisionSource.isCurrent(revision) }
        cancel = cancellation; main.postDelayed(timeout, 120_000)
        val weak = WeakReference(this); val handler = main; val network = transport; val executor = worker
        try {
            worker.execute {
                val result = runCatching { AiRemoteTools.connect(connection, network, executor, cancellation) }
                if (!handler.post { weak.get()?.connected(cancellation, result) ?: result.getOrNull()?.close() }) result.getOrNull()?.close()
            }
        } catch (_: RejectedExecutionException) { connected(cancellation, Result.failure(IllegalStateException("Remote worker unavailable"))) }
    }
    private fun connected(owner: AiRemoteCancellation, result: Result<AiRemoteTools>) {
        if (cancel !== owner || !live() || !networkAllowed() || !owner.live()) { result.getOrNull()?.close(); return }
        main.removeCallbacks(timeout); cancel = null; busy = false
        result.fold({ bundle = it; review(it) }, { render(); status.setText(R.string.ai_remote_failed) })
    }
    private fun review(value: AiRemoteTools) {
        rows.removeAllViews(); rows.addView(status)
        status.text = activity.getString(R.string.ai_remote_review, value.connection.name, value.definitions.size, value.unsupported)
        val selected = linkedSetOf<String>()
        value.definitions.forEach { definition ->
            rows.addView(CheckBox(activity).apply {
                text = definition.title; isSaveEnabled = false
                setOnCheckedChangeListener { box, checked ->
                    if (checked && selected.size >= 32) { box.isChecked = false; return@setOnCheckedChangeListener }
                    if (checked) selected += definition.id else selected -= definition.id
                }
            })
            rows.addView(TextView(activity).apply { text = activity.getString(R.string.ai_remote_definition, definition.description, definition.schema) })
        }
        rows.addView(button(R.string.ai_remote_apply) { applyReviewed(value, selected.toSet()) })
    }
    private fun applyReviewed(value: AiRemoteTools, selected: Set<String>) {
        if (!live() || busy || selected.isEmpty() || !networkAllowed() || !revisions.isCurrent(bundleRevision)) return
        busy = true; val expected = library.revision
        val weak = WeakReference(this); val repository = repository; val handler = main
        val flag = closed; val dataSource = data; val token = ownerData
        try {
            persistence.execute {
                val fresh = runCatching { repository.read { !flag.get() && dataSource.isCurrent(token) }.revision == expected }.getOrDefault(false)
                handler.post { weak.get()?.let { owner ->
                    owner.busy = false
                    if (fresh && owner.live() && owner.networkAllowed() && owner.revisions.isCurrent(owner.bundleRevision) && owner.bundle === value && owner.apply(value, selected)) {
                        owner.bundle = null; owner.close()
                    } else if (owner.live()) { value.close(); owner.bundle = null; owner.render(); owner.status.setText(R.string.ai_remote_failed) }
                } }
            }
        } catch (_: RejectedExecutionException) { busy = false; status.setText(R.string.ai_remote_failed) }
    }
    private fun field(hint: Int, limit: Int, value: String) = EditText(activity).apply {
        setHint(hint); setText(value); isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(limit)); privateView(this)
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
    private fun button(label: Int, action: () -> Unit) = MaterialButton(activity).apply {
        setText(label); isEnabled = !busy; setOnClickListener { action() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacks(timeout); cancel?.cancel(); cancel = null; bundle?.close(); bundle = null
        session.close(); editor?.dismiss(); editor = null; library = AiConnectionLibrary(); rows.removeAllViews(); dialog.dismiss()
    }
    private fun privateView(view: View) {
        view.isSaveEnabled = false; view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
}
