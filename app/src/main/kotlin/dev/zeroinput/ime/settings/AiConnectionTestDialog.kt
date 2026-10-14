package dev.zeroinput.ime.settings

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ai.AiProbeState
import dev.zeroinput.userdata.AiConfiguration

/** Owns a single visible test and invalidates every callback when cancelled or replaced. */
internal class AiConnectionTestDialog(
    private val context: Context,
    private val start: (AiConfiguration, (AiProbeState) -> Unit) -> AutoCloseable,
    private val secure: (AlertDialog, () -> Unit) -> AlertDialog,
) {
    private var request: AutoCloseable? = null
    private var generation = 0L
    private var dialog: AlertDialog? = null
    private var status: TextView? = null

    fun show(configuration: () -> AiConfiguration) {
        cancel()
        dialog?.dismiss()
        val label = TextView(context).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            isSaveEnabled = false
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val window = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_test_model)
            .setView(label).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_test_retry, null).create()
        dialog = window
        status = label
        secure(window) {
            // Dismiss is posted by Android; an old window must not cancel its replacement.
            if (dialog === window) { cancel(); dialog = null; status = null }
        }
        fun run() {
            cancel()
            val token = generation
            val value = configuration()
            val profile = value.activeProvider()
            val destination = profile?.let { runCatching {
                dev.zeroinput.ai.api.AiEndpoint.parse(it.endpoint, it.protocol).generation.toString()
            }.getOrNull() }.orEmpty()
            window.setMessage(context.getString(R.string.ai_test_notice, profile?.name.orEmpty(), value.activeModel()) +
                "\n" + destination)
            if (!value.enabled || !value.networkAllowed) {
                label.setText(R.string.ai_setup_network_required)
                return
            }
            val active = start(value) { state ->
                if (token != generation || !window.isShowing) return@start
                label.text = when (state) {
                    AiProbeState.Running -> context.getString(R.string.ai_test_running)
                    AiProbeState.Available -> context.getString(R.string.ai_test_available)
                    AiProbeState.Cancelled -> context.getString(R.string.ai_test_cancelled)
                    is AiProbeState.Failed -> aiSettingsError(context, state.error)
                }
                window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = state != AiProbeState.Running
            }
            if (token == generation) request = active else active.close()
        }
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { run() }
        run()
    }

    fun cancel() {
        generation++
        request?.close(); request = null
        status?.setText(R.string.ai_test_cancelled)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
    }
}
