package dev.zeroinput.ime.settings

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.AiModelCatalogEvent
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.aiErrorMessage
import dev.zeroinput.userdata.AiConfiguration

/** Discovery changes only this protected editor's draft, after explicit model selection. */
internal class AiModelPicker(
    private val context: Context,
    private val models: EditText,
    private val configuration: () -> AiConfiguration,
    private val start: (AiConfiguration, (AiModelCatalogEvent) -> Unit) -> AutoCloseable,
    private val secure: (AlertDialog) -> AlertDialog,
) : AutoCloseable {
    private val status = TextView(context).apply { isSaveEnabled = false }
    private val fetch = MaterialButton(context).apply {
        setText(R.string.ai_fetch_models); isAllCaps = false
        setOnClickListener { fetch() }
    }
    private var generation = 0L
    private var request: AutoCloseable? = null
    private var selection: AlertDialog? = null
    private val observer = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { cancel() }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private var observed = emptyList<EditText>()

    fun attach(fields: LinearLayout, endpoint: EditText, key: EditText) {
        fields.addView(fetch)
        fields.addView(status)
        observed = listOf(endpoint, key, models)
        observed.forEach { it.addTextChangedListener(observer) }
    }

    private fun fetch() {
        cancel()
        val token = generation
        status.visibility = android.view.View.VISIBLE
        fetch.isEnabled = false
        val active = start(configuration()) { event ->
            if (generation != token) return@start
            fetch.isEnabled = event != AiModelCatalogEvent.Started
            when (event) {
                AiModelCatalogEvent.Started -> status.setText(R.string.ai_models_loading)
                AiModelCatalogEvent.Cancelled -> status.setText(R.string.ai_test_cancelled)
                is AiModelCatalogEvent.Failed -> {
                    status.text = context.getString(R.string.ai_models_failed, context.getString(aiErrorMessage(event.error)))
                }
                is AiModelCatalogEvent.Completed -> {
                    status.text = context.resources.getQuantityString(R.plurals.ai_models_found, event.models.size, event.models.size)
                    choose(event.models, token)
                }
            }
        }
        if (generation == token) request = active else active.close()
    }

    private fun choose(found: List<String>, token: Long) {
        val previous = modelNames(models)
        val entries = (previous + found).distinct()
        val selected = previous.toMutableSet()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_models_select)
            .setMultiChoiceItems(entries.toTypedArray(), entries.map { it in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected += entries[index] else selected -= entries[index]
            }.setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_models_apply, null).create()
        selection = secure(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (generation != token) { dialog.dismiss(); return@setOnClickListener }
            if (selected.size !in 1..32) {
                android.widget.Toast.makeText(context, R.string.ai_provider_invalid, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                models.setText(entries.filter { it in selected }.joinToString(", "))
                dialog.dismiss()
            }
        }
    }

    fun cancel() {
        generation++
        request?.close(); request = null
        selection?.dismiss(); selection = null
        status.text = ""
        status.visibility = android.view.View.GONE
        fetch.isEnabled = true
    }

    override fun close() {
        cancel()
        observed.forEach { it.removeTextChangedListener(observer) }
        observed = emptyList()
    }

    companion object {
        fun modelNames(field: EditText): List<String> = field.text.toString().split(',', '\n', '，')
            .map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
