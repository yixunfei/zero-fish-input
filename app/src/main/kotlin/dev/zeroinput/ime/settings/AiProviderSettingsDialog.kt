package dev.zeroinput.ime.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import dev.zeroinput.ime.ai.AiProbeState
import dev.zeroinput.ime.ui.aiErrorMessage
import com.google.android.material.button.MaterialButton
import java.util.UUID

/** Settings-only editor. Each operation publishes one immutable configuration snapshot. */
internal class AiProviderSettingsDialog(
    private val context: Context,
    private val configuration: () -> AiConfiguration,
    private val persist: (AiConfiguration, (Boolean) -> Unit) -> Unit,
    private val startTest: ((AiProbeState) -> Unit) -> AutoCloseable = { callback ->
        callback(AiProbeState.Failed(dev.zeroinput.ai.api.AiProviderError.Configuration("Probe unavailable")))
        AutoCloseable {}
    },
    private val startDiscovery: (AiConfiguration, (dev.zeroinput.ai.api.AiModelCatalogEvent) -> Unit) -> AutoCloseable = { _, callback ->
        callback(dev.zeroinput.ai.api.AiModelCatalogEvent.Failed(dev.zeroinput.ai.api.AiProviderError.Configuration("Discovery unavailable")))
        AutoCloseable {}
    },
) {
    private val modelPickers = mutableSetOf<AiModelPicker>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private var saving = false
    private var test: AutoCloseable? = null
    private var testStatus: android.widget.TextView? = null
    private var testRetry: android.widget.Button? = null
    private var testGeneration = 0L
    val isShowing: Boolean get() = dialogs.any { it.isShowing }
    fun dismiss() { cancelTests(); dialogs.toList().forEach { it.dismiss() } }
    fun cancelTests() {
        modelPickers.toList().forEach { it.cancel() }
        testGeneration++
        test?.close(); test = null
        testStatus?.setText(R.string.ai_test_cancelled)
        testRetry?.isEnabled = true
    }
    fun show(): AlertDialog = menu()

    private fun save(value: AiConfiguration, completed: (Boolean) -> Unit = {}) {
        if (saving) { completed(false); return }
        cancelTests()
        saving = true
        persist(value) { success -> saving = false; completed(success) }
    }

    private fun profiles(value: AiConfiguration): List<AiProviderProfile> = value.providers

    private fun menu(): AlertDialog {
        val value = configuration()
        val entries = profiles(value)
        val labels = entries.map { profile ->
            val active = profile.id == value.selectedProviderId ||
                value.selectedProviderId == null && value.providers.isEmpty()
            (if (active) "✓ " else "") + profile.name + " · " + profile.selectedModel
        }.toTypedArray()
        val saveHistory = MaterialSwitch(context).apply {
            setText(R.string.ai_save_conversations)
            isChecked = value.saveConversations
            setPadding(dp(24), dp(8), dp(24), dp(8))
            var restoring = false
            setOnCheckedChangeListener { _, checked ->
                if (restoring) return@setOnCheckedChangeListener
                isEnabled = false
                save(configuration().copy(saveConversations = checked)) { success ->
                    isEnabled = true
                    if (!success) {
                        restoring = true
                        isChecked = configuration().saveConversations
                        restoring = false
                    }
                }
            }
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(saveHistory)
            addView(MaterialButton(context).apply {
                setText(R.string.ai_test_model)
                isAllCaps = false
                setOnClickListener { showTest() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                marginStart = dp(24); marginEnd = dp(24)
            })
        }
        return secure(MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_settings_title)
            .setView(controls)
            .setItems(labels) { _, index -> actions(entries[index]) }
            .setPositiveButton(R.string.ai_add_provider) { _, _ -> edit(null) }
            .setNegativeButton(R.string.cancel, null)
            .create())
    }

    private fun showTest() {
        cancelTests()
        val status = android.widget.TextView(context).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isSaveEnabled = false
        }
        testStatus = status
        val value = configuration()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ai_test_model)
            .setMessage(context.getString(R.string.ai_test_notice, value.activeProvider()?.name.orEmpty(), value.activeModel()))
            .setView(status).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_test_retry, null).create()
        fun runTest() {
            cancelTests()
            val token = testGeneration
            val selected = configuration()
            dialog.setMessage(context.getString(R.string.ai_test_notice,
                selected.activeProvider()?.name.orEmpty(), selected.activeModel()))
            test = startTest { state ->
                if (token != testGeneration || !dialog.isShowing) return@startTest
                status.setText(when (state) {
                    AiProbeState.Running -> R.string.ai_test_running
                    AiProbeState.Available -> R.string.ai_test_available
                    AiProbeState.Cancelled -> R.string.ai_test_cancelled
                    is AiProbeState.Failed -> aiErrorMessage(state.error)
                })
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = state != AiProbeState.Running
            }
        }
        secure(dialog) { cancelTests(); if (testStatus === status) { testStatus = null; testRetry = null } }
        testRetry = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { runTest() }
        runTest()
    }

    private fun actions(profile: AiProviderProfile) {
        val names = arrayOf(
            context.getString(R.string.ai_use_provider), context.getString(R.string.ai_choose_model),
            context.getString(R.string.ai_edit_provider), context.getString(R.string.delete),
        )
        secure(MaterialAlertDialogBuilder(context).setTitle(profile.name)
            .setItems(names) { _, action -> when (action) {
                0 -> mutate(profile.id) { it }
                1 -> chooseModel(profile)
                2 -> edit(profile)
                3 -> delete(profile)
            } }.setNegativeButton(R.string.cancel, null).create())
    }

    private fun chooseModel(profile: AiProviderProfile) {
        secure(MaterialAlertDialogBuilder(context).setTitle(R.string.ai_choose_model)
            .setSingleChoiceItems(profile.models.toTypedArray(), profile.models.indexOf(profile.selectedModel)) { dialog, index ->
                mutate(profile.id) { it.copy(selectedModel = profile.models[index]) }
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).create())
    }

    private fun delete(profile: AiProviderProfile) {
        secure(MaterialAlertDialogBuilder(context).setTitle(R.string.ai_delete_provider)
            .setMessage(profile.name).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val current = configuration()
                val remaining = profiles(current).filterNot { it.id == profile.id }
                save(current.copy(providers = remaining,
                    selectedProviderId = current.selectedProviderId?.takeIf { id -> remaining.any { it.id == id } }
                        ?: remaining.firstOrNull()?.id))
            }.create())
    }

    private fun mutate(id: String, change: (AiProviderProfile) -> AiProviderProfile) {
        val current = configuration()
        save(current.copy(providers = profiles(current).map { if (it.id == id) change(it) else it },
            selectedProviderId = id))
    }

    private fun editorFields() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30)
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    private fun edit(existing: AiProviderProfile?) {
        val fields = editorFields()
        fun field(hint: Int, text: String, max: Int, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).also {
            it.hint = context.getString(hint); it.setText(text); it.inputType = type
            it.filters = arrayOf(InputFilter.LengthFilter(max)); it.isSaveEnabled = false
            it.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            fields.addView(it)
        }
        val name = field(R.string.ai_provider_name, existing?.name.orEmpty(), 64)
        val endpoint = field(R.string.ai_endpoint_hint, existing?.endpoint ?: DEFAULT_ENDPOINT, 512,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val key = field(R.string.ai_key_hint, "", 512,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val models = field(R.string.ai_models_hint, existing?.models?.joinToString(", ").orEmpty(), 8192,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2; maxLines = 4
        }
        fields.removeView(models)
        val picker = AiModelPicker(context, models, {
            val draft = AiProviderProfile(existing?.id ?: "draft", name.text.toString(), endpoint.text.toString().trim(),
                key.text.toString().ifBlank { existing?.apiKey.orEmpty() }, emptyList(), "")
            configuration().copy(providers = listOf(draft), selectedProviderId = draft.id)
        }, startDiscovery, { secure(it) }).also {
            modelPickers += it
            it.attach(fields, endpoint, key)
        }
        fields.addView(android.widget.TextView(context).apply {
            setText(R.string.ai_selected_models)
            setPadding(0, dp(8), 0, 0)
        })
        fields.addView(models)
        val images = MaterialSwitch(context).apply {
            setText(R.string.ai_support_images); isChecked = existing?.supportsImages == true
        }
        val audio = MaterialSwitch(context).apply {
            setText(R.string.ai_support_audio); isChecked = existing?.supportsAudio == true
        }
        fields.addView(images); fields.addView(audio)
        val selectedModel = existing?.selectedModel ?: context.getString(R.string.ai_first_model)
        images.text = context.getString(R.string.ai_model_images, selectedModel)
        audio.text = context.getString(R.string.ai_model_audio, selectedModel)
        val scroll = ScrollView(context).apply { addView(fields) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(
            if (existing == null) R.string.ai_add_provider else R.string.ai_edit_provider)
            .setView(scroll).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saving) return@setOnClickListener
                val modelList = AiModelPicker.modelNames(models)
                val providerName = name.text.toString().trim()
                if (providerName.isBlank() || modelList.isEmpty() || modelList.size > 32) {
                    models.error = context.getString(R.string.ai_provider_invalid); return@setOnClickListener
                }
                val profile = AiProviderProfile(existing?.id ?: UUID.randomUUID().toString(), providerName,
                    endpoint.text.toString().trim(), key.text.toString().ifBlank { existing?.apiKey.orEmpty() },
                    modelList, existing?.selectedModel?.takeIf { it in modelList } ?: modelList.first(),
                    (existing?.imageModels.orEmpty() intersect modelList.toSet()).let {
                        updateCapability(it, modelList, existing?.selectedModel, images.isChecked)
                    },
                    (existing?.audioModels.orEmpty() intersect modelList.toSet()).let {
                        updateCapability(it, modelList, existing?.selectedModel, audio.isChecked)
                    })
                val current = configuration()
                val updated = profiles(current).filterNot { it.id == profile.id } + profile
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                save(current.copy(providers = updated, selectedProviderId = profile.id)) { success ->
                    if (success) dialog.dismiss()
                    else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        endpoint.error = context.getString(R.string.ai_provider_save_failed)
                    }
                }
            }
        }
        secure(dialog) {
            picker.close(); modelPickers -= picker
            listOf(name, endpoint, models, key).forEach { it.text?.clear() }
        }
    }

    private fun secure(dialog: AlertDialog, clear: () -> Unit = {}): AlertDialog = dialog.also {
        dialogs += it
        it.setOnDismissListener { dialogs -= dialog; clear() }
        it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        it.show()
    }

    private fun updateCapability(saved: Set<String>, models: List<String>, previous: String?, enabled: Boolean): Set<String> {
        val target = previous ?: models.first()
        if (target !in models) return saved
        return if (enabled) saved + target else saved - target
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
    }
}
