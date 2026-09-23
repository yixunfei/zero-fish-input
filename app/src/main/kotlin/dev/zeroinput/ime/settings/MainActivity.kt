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

    private val languagePackPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        worker.execute {
            val result = runCatching {
                graph.languagePacks.install(uri).also { graph.refreshLanguagePacks() }
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "已导入 ${it.manifest.displayName}", Toast.LENGTH_SHORT).show()
                    render()
                }
                    .onFailure { Toast.makeText(this, it.message ?: "语言包导入失败", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = SettingsScreenView(this)
        setContentView(screen)
        bindScreen()
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
        worker.execute {
            graph.refreshLanguagePacks()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render()
            }
        }
        render()
    }

    override fun onDestroy() {
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
            worker.execute {
                val pack = graph.languagePacks.listInstalled().firstOrNull { it.key == key }
                if (pack != null) graph.languagePacks.setEnabled(pack, enabled)
                graph.refreshLanguagePacks()
                runOnUiThread { if (!isFinishing && !isDestroyed) render() }
            }
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
            worker.execute {
                val removed = graph.languagePacks.removeByKey(key)
                graph.refreshLanguagePacks()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (removed) Toast.makeText(this, "语言包已删除", Toast.LENGTH_SHORT).show()
                        render()
                    }
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
                worker.execute {
                    graph.clearPersonalizationData()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this, R.string.personal_data_cleared, Toast.LENGTH_SHORT).show()
                            render()
                        }
                    }
                }
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
            ),
        )
    }
}
