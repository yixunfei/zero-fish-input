package dev.zeroinput.ime.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ZeroInputService
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.ime.clipboardguard.ClipboardGuardSettingsActivity
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.engine.rime.RimeRuntimeState
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.languagepack.LanguagePackLanguage
import dev.zeroinput.userdata.AiConfiguration
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread(
        name = "zeroinput-settings",
        queueCapacity = 8,
    )
    private lateinit var screen: SettingsScreenView
    private var runtimeObserver: AutoCloseable? = null
    private var languagePackObserver: AutoCloseable? = null
    private var clipboardAuthGeneration = 0L
    private var clipboardAuthRequest: AuthenticationBroker.RequestHandle? = null
    private var aiConfig = AiConfiguration()
    private var aiConfigReady = false
    private var aiDialog: androidx.appcompat.app.AlertDialog? = null
    private val operations = SettingsTaskRunner(
        worker = worker,
        post = { callback -> runOnUiThread { callback() } },
        isActive = { !isFinishing && !isDestroyed },
        onFailure = {
            Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show()
            render()
        },
    )

    private val languagePackPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        operations.execute(
            work = {
                graph.languagePacks.install(uri).also { graph.refreshLanguagePacks() }
            },
        ) { pack ->
            Toast.makeText(this, "已导入 ${pack.manifest.displayName}", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = SettingsScreenView(this)
        setContentView(screen)
        bindScreen()
        if (intent.getBooleanExtra(EXTRA_FUZZY_SETTINGS, false)) screen.post { screen.showFuzzySettings() }
        runtimeObserver = graph.rime.runtime.addStateListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render()
            }
        }
        languagePackObserver = graph.addLanguagePackListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        graph.readAiConfiguration { result -> runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                aiConfig = result.getOrDefault(AiConfiguration())
                aiConfigReady = result.isSuccess
                render()
            }
        } }
        operations.execute({ graph.refreshLanguagePacks() }) { render() }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FUZZY_SETTINGS, false)) screen.post { screen.showFuzzySettings() }
    }

    companion object {
        const val EXTRA_FUZZY_SETTINGS = "dev.zeroinput.ime.FUZZY_SETTINGS"
    }

    override fun onDestroy() {
        aiDialog?.dismiss()
        aiDialog = null
        operations.close()
        clipboardAuthGeneration++
        clipboardAuthRequest?.close()
        clipboardAuthRequest = null
        runtimeObserver?.close()
        runtimeObserver = null
        languagePackObserver?.close()
        languagePackObserver = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun bindScreen() {
        screen.onEnableRequested = {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        screen.onSwitchRequested = {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        screen.onLearningChanged = { graph.settings.learningEnabled = it; render() }
        screen.onIncognitoChanged = { graph.settings.incognitoMode = it; render() }
        screen.onSecureClipboardChanged = ::setSecureClipboardEnabled
        screen.onClipboardGuardRequested = { startActivity(Intent(this, ClipboardGuardSettingsActivity::class.java)) }
        screen.onHapticsChanged = { graph.settings.hapticFeedbackEnabled = it; render() }
        screen.onWordAssociationsChanged = { graph.settings.wordAssociationsEnabled = it; render() }
        screen.onAiEnabledChanged = { updateAiConfig { copy(enabled = it) } }
        screen.onAiNetworkChanged = { updateAiConfig { copy(networkAllowed = it) } }
        screen.onAiSettingsRequested = ::showAiSettings
        screen.onAiDataClearRequested = ::confirmClearAiData
        screen.onAppearanceRequested = { startActivity(Intent(this, KeyboardAppearanceActivity::class.java)) }
        screen.onChineseOptionsChanged = { graph.settings.chineseInputOptions = it; render() }
        screen.onModelRankingChanged = { graph.settings.experimentalModelRanking = it; render() }
        screen.onChineseEngineChanged = {
            graph.settings.chineseEngine = it
            graph.settings.lastLanguagePackKey = null
            render()
        }
        screen.onBuiltInEngineSelected = {
            graph.settings.lastLanguagePackKey = null
            graph.settings.lastLanguage = InputLanguage.CHINESE
            render()
        }
        screen.onDictionaryRequested = { startActivity(Intent(this, UserDictionaryActivity::class.java)) }
        screen.onExpressionsRequested = {
            startActivity(Intent(this, dev.zeroinput.ime.expressions.ExpressionManagerActivity::class.java))
        }
        screen.onPersonalDataClearRequested = ::confirmClearPersonalData
        screen.onSecureClipboardRequested = {
            startActivity(Intent(this, SecureClipboardManagerActivity::class.java))
        }
        screen.onLanguagePackRequested = {
            languagePackPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        screen.onLanguagePackEnabledChanged = { key, enabled ->
            operations.execute({
                val pack = checkNotNull(graph.languagePacks.listInstalled().firstOrNull { it.key == key })
                val changed = graph.languagePacks.setEnabled(pack, enabled)
                graph.refreshLanguagePacks()
                check(changed)
            }) { render() }
        }
        screen.onLanguagePackSelected = { key ->
            if (graph.languagePackRegistry.contains(key)) {
                val pack = graph.installedLanguagePacks().firstOrNull { it.key == key }
                val language = pack?.manifest?.languageTag?.let(LanguagePackLanguage::fromTag)
                if (language == null) {
                    // The registry normally filters these packages out. Keep
                    // this guard at the UI boundary as well so a stale
                    // snapshot can never silently turn an unsupported pack
                    // into an English session.
                    Toast.makeText(this, "该语言包暂不支持", Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    graph.settings.lastLanguage = language
                    graph.settings.lastLanguagePackKey = key
                    Toast.makeText(this, "已选择语言包，重新打开输入框后生效", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }
        screen.onLanguagePackDeleteRequested = { key ->
            operations.execute({
                val removed = graph.languagePacks.removeByKey(key)
                graph.refreshLanguagePacks()
                check(removed)
            }) {
                Toast.makeText(this, "语言包已删除", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun setSecureClipboardEnabled(enabled: Boolean) {
        if (!enabled) {
            clipboardAuthGeneration++
            clipboardAuthRequest?.close()
            clipboardAuthRequest = null
            graph.settings.secureClipboardEnabled = false
            render()
            return
        }
        val generation = ++clipboardAuthGeneration
        clipboardAuthRequest?.close()
        clipboardAuthRequest = AuthenticationBroker.requestCancellable(this) authCallback@{ grant ->
            if (generation != clipboardAuthGeneration) return@authCallback
            clipboardAuthRequest = null
            if (isFinishing || isDestroyed) return@authCallback
            graph.settings.secureClipboardEnabled = grant != null
            render()
        }
    }

    private fun confirmClearPersonalData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_personal_data)
            .setMessage(R.string.clear_personal_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                operations.execute({ graph.clearPersonalizationData() }) {
                    Toast.makeText(this, R.string.personal_data_cleared, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .show()
    }

    private fun updateAiConfig(change: AiConfiguration.() -> AiConfiguration) {
        if (!aiConfigReady) return
        val next = aiConfig.change()
        aiConfigReady = false
        graph.updateAiConfiguration(next) { success -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // A rejected endpoint or failed write leaves the saved settings
            // intact. Preserve them for the next edit while reflecting the
            // graph's immediate network revocation in both switches.
            aiConfig = if (success) next else aiConfig.copy(enabled = false, networkAllowed = false)
            aiConfigReady = true
            render()
            Toast.makeText(this, if (success) R.string.ai_settings_saved else R.string.operation_failed, Toast.LENGTH_SHORT).show()
        } }
    }

    private fun showAiSettings() {
        if (!aiConfigReady || aiDialog != null) return
        val current = aiConfig
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val endpoint = EditText(this).apply { hint = getString(R.string.ai_endpoint_hint); setText(current.endpoint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val model = EditText(this).apply { hint = getString(R.string.ai_model_hint); setText(current.model) }
        val key = EditText(this).apply {
            hint = getString(R.string.ai_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val save = MaterialSwitch(this).apply { text = getString(R.string.ai_save_conversations); isChecked = current.saveConversations }
        fields.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            fields.importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        listOf(endpoint, model, key).forEach {
            it.isSaveEnabled = false
            it.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            it.filters = arrayOf(android.text.InputFilter.LengthFilter(if (it === model) 128 else 512))
        }
        fields.addView(endpoint); fields.addView(model); fields.addView(key); fields.addView(save)
        aiDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_settings_title)
            .setView(fields)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val enteredKey = key.text?.toString().orEmpty()
                val next = current.copy(
                    endpoint = endpoint.text?.toString()?.trim().orEmpty(),
                    model = model.text?.toString()?.trim().orEmpty(),
                    apiKey = enteredKey.ifBlank { current.apiKey },
                    saveConversations = save.isChecked,
                )
                updateAiConfig { next }
            }
            .create().also { dialog ->
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnDismissListener {
                    key.text?.clear()
                    endpoint.text?.clear()
                    model.text?.clear()
                    if (aiDialog === dialog) aiDialog = null
                }
                dialog.show()
            }
    }

    override fun onStop() {
        // Keep the bounded draft in this protected window while the user visits
        // another app. Explicit dismissal or destruction clears its fields.
        if (isFinishing) aiDialog?.dismiss()
        super.onStop()
    }

    private fun confirmClearAiData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_clear_data)
            .setMessage(R.string.ai_data_clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                aiConfigReady = false
                graph.clearAiData { success -> runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    aiConfig = AiConfiguration()
                    aiConfigReady = true
                    render()
                    Toast.makeText(this, if (success) R.string.ai_data_cleared else R.string.operation_failed, Toast.LENGTH_SHORT).show()
                } }
            }
            .show()
    }

    private fun render() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        val component = ComponentName(this, ZeroInputService::class.java)
        val isEnabled = inputMethodManager.enabledInputMethodList.any {
            it.serviceInfo.packageName == packageName && it.serviceInfo.name == component.className
        }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val choice = graph.settings.chineseEngine
        val engine = if (choice == ChineseEngineChoice.DICTIONARY_TEST) getString(R.string.engine_dictionary_name) else when (graph.rime.runtime.state) {
            RimeRuntimeState.READY -> getString(R.string.engine_status_ready)
            RimeRuntimeState.FAILED -> getString(R.string.engine_status_failed)
            RimeRuntimeState.INITIALIZING -> getString(R.string.engine_status_preparing)
            RimeRuntimeState.NOT_INITIALIZED -> getString(R.string.engine_status_waiting)
        }
        val selectedPackKey = graph.settings.lastLanguagePackKey
        screen.render(
            SettingsScreenState(
                isEnabled = isEnabled,
                isCurrent = current == component.flattenToShortString() || current == component.flattenToString(),
                learningEnabled = graph.settings.learningEnabled,
                incognitoMode = graph.settings.incognitoMode,
                secureClipboardEnabled = graph.settings.secureClipboardEnabled,
                hapticsEnabled = graph.settings.hapticFeedbackEnabled,
                wordAssociationsEnabled = graph.settings.wordAssociationsEnabled,
                engineStatus = engine,
                chineseOptions = graph.settings.chineseInputOptions,
                experimentalModelRanking = graph.settings.experimentalModelRanking,
                chineseEngine = choice,
                engineCapabilities = graph.chineseEngineDescriptor(choice).capabilities,
                languagePacks = graph.installedLanguagePacks().map { pack ->
                    LanguagePackScreenState(
                        key = pack.key,
                        displayName = pack.manifest.displayName,
                        languageTag = pack.manifest.languageTag,
                        version = pack.manifest.version,
                        enabled = pack.enabled,
                        selected = selectedPackKey == pack.key,
                        available = graph.languagePackRegistry.contains(pack.key),
                    )
                },
                aiEnabled = aiConfig.enabled,
                aiNetworkAllowed = aiConfig.networkAllowed,
                aiEndpoint = aiConfig.endpoint,
                aiModel = aiConfig.model,
                aiKeyConfigured = aiConfig.apiKey.isNotBlank(),
                aiSaveConversations = aiConfig.saveConversations,
            ),
        )
    }
}
