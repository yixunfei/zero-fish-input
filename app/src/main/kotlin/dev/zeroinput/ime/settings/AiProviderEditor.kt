package dev.zeroinput.ime.settings

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ai.api.AiEndpoint
import dev.zeroinput.ai.api.AiModelCatalogEvent
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import java.util.UUID

/** Memory-only provider draft. Validation never mutates the saved configuration. */
internal class AiProviderEditor(
    private val context: Context,
    private val existing: AiProviderProfile?,
    private val configuration: () -> AiConfiguration,
    private val save: (AiConfiguration, (Boolean) -> Unit) -> Unit,
    private val discovery: (AiConfiguration, (AiModelCatalogEvent) -> Unit) -> AutoCloseable,
    private val test: (() -> AiConfiguration) -> Unit,
    private val cancelTest: () -> Unit,
    private val secure: (AlertDialog, () -> Unit) -> AlertDialog,
    private val closed: () -> Unit,
) {
    private val fields = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 3, padding, padding / 3)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30)
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
    private val id = existing?.id ?: UUID.randomUUID().toString()
    private val name = field(R.string.ai_provider_name, existing?.name.orEmpty(), 64)
    private val endpoint = field(R.string.ai_endpoint_hint, existing?.endpoint.orEmpty(), 512,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
        helperText = context.getString(R.string.ai_endpoint_example)
    }
    private val key = field(R.string.ai_key_hint, "", 512,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
        input.transformationMethod = PasswordTransformationMethod.getInstance()
        helperText = context.getString(if (existing == null) R.string.ai_key_new else R.string.ai_key_keep)
    }
    private val models = field(R.string.ai_models_hint, existing?.models?.joinToString(", ").orEmpty(), 8192,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
        input.minLines = 2; input.maxLines = 4
        helperText = context.getString(R.string.ai_models_help)
    }
    private var selectedModel = existing?.selectedModel.orEmpty()
    private val selected = AiProviderChoiceField(context, R.string.ai_default_model, R.string.ai_choose_model) { index ->
        val model = AiModelPicker.modelNames(models.input).getOrNull(index)
        if (model != null && model != selectedModel) {
            cancelTest(); selectedModel = model; renderCapabilities()
        }
    }
    private val noModels = TextView(context).apply { setText(R.string.ai_models_empty) }
    private var updatingModels = false
    private val imageModels = existing?.imageModels.orEmpty().toMutableSet()
    private val audioModels = existing?.audioModels.orEmpty().toMutableSet()
    private val images = MaterialSwitch(context)
    private val audio = MaterialSwitch(context)
    private val network = AiNetworkSettingsView(context, configuration, save)
    private var picker: AiModelPicker? = null
    private var saving = false
    private val protocol: AiProtocolFields = AiProtocolFields(context, existing, { endpoint.input.text.toString() }) {
        cancel()
        endpoint.error = null
        if (!protocolSupportsAudio()) audioModels.clear()
        renderCapabilities()
    }

    private fun protocolSupportsAudio(): Boolean = protocol.protocol.supportsAudio

    fun show() {
        fields.removeView(models)
        protocol.attach(fields)
        fields.addView(network)
        picker = AiModelPicker(context, models.input, { draft(false, false) }, discovery, { secure(it, {}) },
            validate = { validateConnection() }).also { it.attach(fields, endpoint.input, key.input) }
        fields.addView(models)
        fields.addView(noModels)
        fields.addView(selected)
        bindModels()
        fields.addView(MaterialButton(context).apply {
            setText(R.string.ai_test_draft); isAllCaps = false
            setOnClickListener { if (validateConnection() && validateModels()) test { draft(false) } }
        })
        fields.addView(images); fields.addView(audio)
        fields.addView(TextView(context).apply { setText(R.string.ai_draft_test_notice) })
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.ai_add_provider else R.string.ai_edit_provider)
            .setView(ScrollView(context).apply { addView(fields) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        secure(dialog) {
            cancel(); picker?.close()
            listOf(name, endpoint, key, models).forEach { it.input.text?.clear() }
            closed()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { persist(dialog) }
    }

    fun cancel() { picker?.cancel(); cancelTest() }

    private fun field(hint: Int, value: String, max: Int, type: Int = InputType.TYPE_CLASS_TEXT) =
        AiProviderTextField(context, hint, value, max, type).also(fields::addView)

    private fun bindModels() {
        images.setOnCheckedChangeListener { _, checked ->
            if (!updatingModels && selectedModel.isNotEmpty()) {
                if (checked) imageModels += selectedModel else imageModels -= selectedModel
            }
        }
        audio.setOnCheckedChangeListener { _, checked ->
            if (!updatingModels && selectedModel.isNotEmpty()) {
                if (checked) audioModels += selectedModel else audioModels -= selectedModel
            }
        }
        models.input.addTextChangedListener(watcher { refreshModels() })
        name.input.addTextChangedListener(watcher { name.error = null })
        listOf(endpoint, key).forEach { it.input.addTextChangedListener(watcher { cancelTest(); it.error = null; protocol.render() }) }
        refreshModels()
    }

    private fun refreshModels() {
        cancelTest()
        models.error = null
        val entries = AiModelPicker.modelNames(models.input)
        if (selectedModel !in entries) selectedModel = entries.firstOrNull().orEmpty()
        selected.render(entries, selectedModel)
        noModels.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        renderCapabilities()
    }

    private fun renderCapabilities() {
        updatingModels = true
        val label = selectedModel.ifEmpty { context.getString(R.string.ai_first_model) }
        images.text = context.getString(R.string.ai_model_images, label)
        audio.text = context.getString(R.string.ai_model_audio, label)
        images.isChecked = selectedModel in imageModels
        audio.isChecked = selectedModel in audioModels
        images.isEnabled = selectedModel.isNotEmpty(); audio.isEnabled = selectedModel.isNotEmpty() && protocolSupportsAudio()
        images.visibility = if (selectedModel.isNotEmpty()) View.VISIBLE else View.GONE
        audio.visibility = if (selectedModel.isNotEmpty() && protocolSupportsAudio()) View.VISIBLE else View.GONE
        updatingModels = false
    }

    private fun validateConnection(): Boolean {
        endpoint.error = null; key.error = null
        val validUrl = runCatching { AiEndpoint.parse(endpoint.input.text.toString(), protocol.protocol) }.isSuccess
        if (!validUrl) endpoint.error = context.getString(R.string.ai_protocol_address_invalid)
        val credential = credential()
        val validKey = credential.isNotBlank() && credential.length <= 512 && credential.none { it.isWhitespace() || it.isISOControl() }
        if (!validKey) key.error = context.getString(R.string.ai_key_invalid)
        return validUrl && validKey
    }

    private fun validateModels(): Boolean {
        val values = AiModelPicker.modelNames(models.input)
        val valid = values.size in 1..32 && values.all { it.length <= 128 && it.none(Char::isISOControl) }
        models.error = if (valid) null else context.getString(R.string.ai_models_invalid)
        return valid
    }

    private fun credential() = key.value.ifEmpty { existing?.apiKey.orEmpty().trim() }

    private fun draft(includeAll: Boolean, includeModels: Boolean = true): AiConfiguration {
        val current = configuration()
        val values = if (includeModels) AiModelPicker.modelNames(models.input) else emptyList()
        val profile = AiProviderProfile(id, name.value, endpoint.value, credential(),
            values, selectedModel.takeIf { it in values } ?: values.firstOrNull().orEmpty(),
            imageModels intersect values.toSet(), audioModels intersect values.toSet(), protocol.protocol, protocol.streams)
        return current.copy(providers = (if (includeAll) current.providers.filterNot { it.id == id } else emptyList()) + profile,
            selectedProviderId = id)
    }

    private fun persist(dialog: AlertDialog) {
        if (saving) return
        val validName = name.value.let { it.isNotEmpty() && it.none(Char::isISOControl) }
        name.error = if (validName) null else context.getString(R.string.ai_name_invalid)
        val validConnection = validateConnection()
        val validModels = validateModels()
        if (!validName || !validConnection || !validModels) return
        if (existing == null && configuration().providers.size >= 16) {
            name.error = context.getString(R.string.ai_provider_limit); return
        }
        val value = draft(true)
        saving = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        save(value) { success ->
            saving = false
            if (success) dialog.dismiss() else {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                network.render()
                name.error = context.getString(R.string.ai_provider_save_failed)
            }
        }
    }

    private fun watcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }
}
