package dev.zeroinput.ime.settings

import android.content.Context
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import dev.zeroinput.ime.ai.AiProbeState
import com.google.android.material.button.MaterialButton

/** Settings-only editor. Each operation publishes one immutable configuration snapshot. */
internal class AiProviderSettingsDialog(
    private val context: Context,
    private val configuration: () -> AiConfiguration,
    private val persist: (AiConfiguration, (Boolean) -> Unit) -> Unit,
    private val startTest: (AiConfiguration, (AiProbeState) -> Unit) -> AutoCloseable = { _, callback ->
        callback(AiProbeState.Failed(dev.zeroinput.ai.api.AiProviderError.Configuration("Probe unavailable")))
        AutoCloseable {}
    },
    private val startDiscovery: (AiConfiguration, (dev.zeroinput.ai.api.AiModelCatalogEvent) -> Unit) -> AutoCloseable = { _, callback ->
        callback(dev.zeroinput.ai.api.AiModelCatalogEvent.Failed(dev.zeroinput.ai.api.AiProviderError.Configuration("Discovery unavailable")))
        AutoCloseable {}
    },
) {
    private val editors = mutableSetOf<AiProviderEditor>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private var saving = false
    private val connectionTest = AiConnectionTestDialog(context, startTest, ::secure)
    val isShowing: Boolean get() = dialogs.any { it.isShowing }
    fun dismiss() { cancelTests(); dialogs.toList().forEach { it.dismiss() } }
    fun cancelTests() {
        editors.toList().forEach { it.cancel() }
        connectionTest.cancel()
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
            addView(AiNetworkSettingsView(context, configuration, ::save).apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
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

    private fun showTest() = connectionTest.show(configuration)

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

    private fun edit(existing: AiProviderProfile?) {
        lateinit var editor: AiProviderEditor
        editor = AiProviderEditor(context, existing, configuration, ::save, startDiscovery,
            connectionTest::show, connectionTest::cancel, ::secure, { editors -= editor })
        editors += editor
        editor.show()
    }
    private fun secure(dialog: AlertDialog, clear: () -> Unit = {}): AlertDialog = dialog.also {
        dialogs += it
        it.setOnDismissListener { dialogs -= dialog; clear() }
        it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        it.show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
