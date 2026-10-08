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

class MainActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread(
        name = "zeroinput-settings",
        queueCapacity = 8,
    )
    private lateinit var screen: SettingsScreenView
    private var runtimeObserver: AutoCloseable? = null
    private var languagePackObserver: AutoCloseable? = null
    private var lexiconFailureObserver: AutoCloseable? = null
    private var clipboardAuthGeneration = 0L
    private var clipboardAuthRequest: AuthenticationBroker.RequestHandle? = null
    private var aiConfig = AiConfiguration()
    private var aiConfigReady = false
    private var aiDialog: AiProviderSettingsDialog? = null
    private var languagePackOperationGeneration = 0L
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
            Toast.makeText(this, getString(R.string.language_pack_imported, pack.manifest.displayName), Toast.LENGTH_SHORT).show()
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
        lexiconFailureObserver = graph.userLexicon.addWriteFailureListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, R.string.user_dictionary_write_failed, Toast.LENGTH_LONG).show()
                }
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
        lexiconFailureObserver?.close()
        lexiconFailureObserver = null
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
        screen.onSoundEffectsChanged = { graph.settings.soundEffectsEnabled = it; render() }
        screen.onWordAssociationsChanged = { graph.settings.wordAssociationsEnabled = it; render() }
        screen.onPairedSymbolsChanged = { graph.settings.pairedSymbolsEnabled = it; render() }
        screen.onAiEnabledChanged = { enabled ->
            updateAiConfig {
                copy(
                    enabled = enabled,
                    networkAllowed = if (enabled) networkAllowed else false,
                )
            }
        }
        screen.onAiNetworkChanged = { updateAiConfig { copy(networkAllowed = it) } }
        screen.onAiSettingsRequested = ::showAiSettings
        screen.onAiDataClearRequested = ::confirmClearAiData
        screen.onAiPageSettingsRequested = {
            startActivity(Intent(this, dev.zeroinput.ime.ai.page.PageReferenceSettingsActivity::class.java))
        }
        screen.onAppearanceRequested = { startActivity(Intent(this, KeyboardAppearanceActivity::class.java)) }
        screen.onChineseOptionsChanged = { graph.settings.chineseInputOptions = it; render() }
        screen.onModelRankingChanged = { graph.settings.experimentalModelRanking = it; render() }
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
        screen.onSecureClipboardClearRequested = ::confirmClearSecureClipboard
        screen.onSecureClipboardRequested = {
            startActivity(Intent(this, SecureClipboardManagerActivity::class.java))
        }
        screen.onLanguagePackRequested = {
            languagePackPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        screen.onLanguagePackEnabledChanged = { key, enabled ->
            val ticket = ++languagePackOperationGeneration
            operations.execute({
                val pack = checkNotNull(graph.languagePacks.listInstalled().firstOrNull { it.key == key })
                val changed = graph.languagePacks.setEnabled(pack, enabled)
                graph.refreshLanguagePacks()
                check(changed)
            }) { if (ticket == languagePackOperationGeneration) render() }
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
                    Toast.makeText(this, R.string.language_pack_unsupported, Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    graph.settings.lastLanguage = language
                    graph.settings.lastLanguagePackKey = key
                    Toast.makeText(this, R.string.language_pack_selected, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }
        screen.onLanguagePackDeleteRequested = { key ->
            val ticket = ++languagePackOperationGeneration
            operations.execute({
                val removed = graph.languagePacks.removeByKey(key)
                graph.refreshLanguagePacks()
                check(removed)
            }) {
                if (ticket == languagePackOperationGeneration) {
                    Toast.makeText(this, R.string.language_pack_deleted, Toast.LENGTH_SHORT).show()
                    render()
                }
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

    private fun confirmClearSecureClipboard() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_secure_clipboard)
            .setMessage(R.string.clear_secure_clipboard_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (isFinishing || isDestroyed) return@setPositiveButton
                graph.securePaste.cancel()
                operations.execute({ graph.secureClipboard.purge() }) {
                    Toast.makeText(this, R.string.secure_clipboard_cleared, Toast.LENGTH_SHORT).show()
                    render()
                }
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        }
        dialog.show()
    }

    private fun updateAiConfig(completed: (Boolean) -> Unit = {}, change: AiConfiguration.() -> AiConfiguration) {
        if (!aiConfigReady) { completed(false); return }
        val next = aiConfig.change()
        aiConfigReady = false
        screen.setAiControlsEnabled(false)
        graph.updateAiConfiguration(next) { success -> runOnUiThread {
            if (isFinishing || isDestroyed) { completed(false); return@runOnUiThread }
            // A rejected endpoint or failed write leaves the saved settings
            // intact. Preserve them for the next edit while reflecting the
            // graph's immediate network revocation in both switches.
            aiConfig = if (success) next else aiConfig.copy(enabled = false, networkAllowed = false)
            aiConfigReady = true
            render()
            completed(success)
            Toast.makeText(this, if (success) R.string.ai_settings_saved else R.string.operation_failed, Toast.LENGTH_SHORT).show()
        } }
    }

    private fun showAiSettings() {
        if (!aiConfigReady || aiDialog?.isShowing == true) return
        aiDialog = AiProviderSettingsDialog(this, { aiConfig }, { next, done -> updateAiConfig(done) { next } }, ::startAiTest, ::startAiDiscovery)
            .also { it.show() }
    }

    private fun startAiTest(render: (dev.zeroinput.ime.ai.AiProbeState) -> Unit): AutoCloseable {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val data = graph.aiDataGeneration.current()
        val config = graph.aiConfigurationSnapshot() ?: AiConfiguration()
        val current = { active.get() && !isFinishing && !isDestroyed &&
            graph.aiDataGeneration.isCurrent(data) && graph.aiConfigurationSnapshot() == config }
        val probe = dev.zeroinput.ime.ai.AiProviderProbe(
            provider = { snapshot -> dev.zeroinput.ime.ai.OpenAiCompatibleProvider(
                { if (current()) snapshot else AiConfiguration() },
                graph.aiProbeExecutor, graph.aiCancellationExecutor) },
            current = current,
            post = { callback -> screen.post { callback() } },
            render = render,
        )
        val observer = graph.observeAiConfiguration { screen.post { if (active.get()) probe.cancel() } }
        probe.start(config)
        return AutoCloseable { active.set(false); observer.close(); probe.close() }
    }

    private fun startAiDiscovery(
        draft: AiConfiguration,
        render: (dev.zeroinput.ai.api.AiModelCatalogEvent) -> Unit,
    ): AutoCloseable {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val data = graph.aiDataGeneration.current()
        val saved = graph.aiConfigurationSnapshot()
        val current = { active.get() && !isFinishing && !isDestroyed &&
            graph.aiDataGeneration.isCurrent(data) && graph.aiConfigurationSnapshot() == saved }
        val discovery = dev.zeroinput.ime.ai.AiModelDiscovery(
            provider = { snapshot -> dev.zeroinput.ime.ai.OpenAiCompatibleProvider(
                { if (current()) snapshot else AiConfiguration() }, graph.aiProbeExecutor, graph.aiCancellationExecutor) },
            current = current,
            post = { callback -> screen.post { callback() } },
            render = render,
        )
        val observer = graph.observeAiConfiguration { screen.post { if (active.get()) discovery.cancel() } }
        discovery.start(draft.copy(enabled = saved?.enabled == true, networkAllowed = saved?.networkAllowed == true))
        return AutoCloseable { active.set(false); observer.close(); discovery.close() }
    }

    override fun onStop() {
        aiDialog?.cancelTests()
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
                screen.setAiControlsEnabled(false)
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
        val engine = when (graph.rime.runtime.state) {
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
                soundEffectsEnabled = graph.settings.soundEffectsEnabled,
                wordAssociationsEnabled = graph.settings.wordAssociationsEnabled,
                pairedSymbolsEnabled = graph.settings.pairedSymbolsEnabled,
                engineStatus = engine,
                chineseOptions = graph.settings.chineseInputOptions,
                experimentalModelRanking = graph.settings.experimentalModelRanking,
                engineCapabilities = graph.rime.descriptor.capabilities,
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
                aiEndpoint = aiConfig.activeEndpoint(),
                aiModel = aiConfig.activeModel(),
                aiProviderName = aiConfig.activeProvider()?.name.orEmpty(),
                aiKeyConfigured = aiConfig.activeKey().isNotBlank(),
                aiSaveConversations = aiConfig.saveConversations,
            ),
        )
        screen.setAiControlsEnabled(aiConfigReady)
    }
}
