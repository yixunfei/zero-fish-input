package dev.zeroinput.ime.ai

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R

/** Two explicit decisions: send the selected source, then apply the returned preview. */
internal class AiCompactionDialog(
    private val context: Context,
    input: AiCompactionInput,
    private val start: ((Result<AiCompactionProposal>) -> Unit) -> Boolean,
    private val apply: (AiCompactionProposal) -> Boolean,
    private val cancel: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private var running = false
    private var proposal: AiCompactionProposal? = null
    private val text = TextView(context).apply {
        isSaveEnabled = false; setTextIsSelectable(true)
        val sources = buildList {
            input.summaries.forEach { add(it.text) }
            input.history.forEach {
                add(context.getString(if (it.role == AiRole.USER) dev.zeroinput.ime.ui.R.string.ai_user_message
                    else dev.zeroinput.ime.ui.R.string.ai_assistant_message, it.content))
            }
        }
        this.text = sources.joinToString("\n\n")
    }
    private val notice = TextView(context).apply { setText(R.string.ai_compaction_notice) }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; isSaveEnabled = false
        val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        addView(notice); addView(text)
    }
    private val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_compaction_title)
        .setView(ScrollView(context).apply { addView(body) }).setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ai_compaction_generate, null).create()

    fun show() {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { close() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val candidate = proposal
            if (candidate != null) {
                if (apply(candidate)) { proposal = null; close() } else fail()
            } else if (!running) {
                running = true
                notice.setText(R.string.ai_compaction_working)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                if (!start(::completed)) fail()
            }
        }
    }

    private fun completed(result: Result<AiCompactionProposal>) {
        if (closed || !running) return
        running = false
        result.fold({ value ->
            proposal = value
            notice.setText(R.string.ai_compaction_preview)
            text.text = value.summary
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { setText(R.string.ai_compaction_apply); isEnabled = true }
        }, { fail() })
    }

    private fun fail() {
        if (running || proposal != null) cancel()
        running = false; proposal = null
        notice.setText(R.string.ai_compaction_failed)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    override fun close() {
        if (closed) return
        closed = true
        if (running || proposal != null) cancel()
        running = false; proposal = null
        text.text = ""; body.removeAllViews(); dialog.dismiss()
    }
}
