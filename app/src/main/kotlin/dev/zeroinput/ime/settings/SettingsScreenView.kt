package dev.zeroinput.ime.settings

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.BuildConfig
import dev.zeroinput.ime.R
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EngineCapability
import com.google.android.material.radiobutton.MaterialRadioButton
import android.widget.RadioGroup

data class SettingsScreenState(
    val isEnabled: Boolean,
    val isCurrent: Boolean,
    val learningEnabled: Boolean,
    val incognitoMode: Boolean,
    val secureClipboardEnabled: Boolean,
    val hapticsEnabled: Boolean,
    val engineStatus: String,
    val chineseOptions: ChineseInputOptions = ChineseInputOptions(),
    val experimentalModelRanking: Boolean = false,
    val wordAssociationsEnabled: Boolean = true,
    val languagePacks: List<LanguagePackScreenState> = emptyList(),
    val chineseEngine: ChineseEngineChoice = ChineseEngineChoice.RIME,
    val engineCapabilities: Set<EngineCapability> = EngineCapability.entries.toSet(),
    val aiEnabled: Boolean = false,
    val aiNetworkAllowed: Boolean = false,
    val aiEndpoint: String = "",
    val aiModel: String = "",
    val aiKeyConfigured: Boolean = false,
    val aiSaveConversations: Boolean = false,
)

data class LanguagePackScreenState(
    val key: String,
    val displayName: String,
    val languageTag: String,
    val version: String,
    val enabled: Boolean,
    val selected: Boolean,
    val available: Boolean,
)

class SettingsScreenView(context: Context) : ScrollView(context) {
    var onEnableRequested: () -> Unit = {}
    var onSwitchRequested: () -> Unit = {}
    var onLearningChanged: (Boolean) -> Unit = {}
    var onIncognitoChanged: (Boolean) -> Unit = {}
    var onSecureClipboardChanged: (Boolean) -> Unit = {}
    var onHapticsChanged: (Boolean) -> Unit = {}
    var onWordAssociationsChanged: (Boolean) -> Unit = {}
    var onDictionaryRequested: () -> Unit = {}
    var onExpressionsRequested: () -> Unit = {}
    var onAppearanceRequested: () -> Unit = {}
    var onPersonalDataClearRequested: () -> Unit = {}
    var onSecureClipboardRequested: () -> Unit = {}
    var onClipboardGuardRequested: () -> Unit = {}
    var onLanguagePackRequested: () -> Unit = {}
    var onLanguagePackEnabledChanged: (String, Boolean) -> Unit = { _, _ -> }
    var onLanguagePackSelected: (String) -> Unit = {}
    var onLanguagePackDeleteRequested: (String) -> Unit = {}
    var onChineseOptionsChanged: (ChineseInputOptions) -> Unit = {}
    var onModelRankingChanged: (Boolean) -> Unit = {}
    var onBuiltInEngineSelected: () -> Unit = {}
    var onChineseEngineChanged: (ChineseEngineChoice) -> Unit = {}
    var onAiEnabledChanged: (Boolean) -> Unit = {}
    var onAiNetworkChanged: (Boolean) -> Unit = {}
    var onAiSettingsRequested: () -> Unit = {}
    var onAiDataClearRequested: () -> Unit = {}

    private var suppressSwitchCallbacks = false
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(32))
    }
    private val enabledStatus = valueText()
    private val currentStatus = valueText()
    private val engineStatus = valueText()
    private val chineseSettings = ChineseSettingsView(context).apply {
        onOptionsChanged = { onChineseOptionsChanged(it) }
    }

    fun showFuzzySettings() { chineseSettings.showFuzzySettings() }
    private val languagePackContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val learningSwitch = settingSwitch(context.getString(R.string.setting_learning)) { onLearningChanged(it) }
    private val modelSwitch = settingSwitch(context.getString(R.string.experimental_model_ranking)) { onModelRankingChanged(it) }
    private val incognitoSwitch = settingSwitch(context.getString(R.string.setting_incognito)) { onIncognitoChanged(it) }
    private val secureClipboardSwitch = settingSwitch(context.getString(R.string.secure_clipboard)) { onSecureClipboardChanged(it) }
    private val hapticsSwitch = settingSwitch(context.getString(R.string.setting_haptics)) { onHapticsChanged(it) }
    private val associationSwitch = settingSwitch(context.getString(R.string.setting_word_associations)) { onWordAssociationsChanged(it) }
    private val aiEnabledSwitch = settingSwitch(context.getString(R.string.ai_enabled)) { onAiEnabledChanged(it) }
    private val aiNetworkSwitch = settingSwitch(context.getString(R.string.ai_network)) { onAiNetworkChanged(it) }
    private val aiStatus = valueText()
    private val engineButtons = ChineseEngineChoice.entries.associateWith { choice ->
        MaterialRadioButton(context).apply {
            id = View.generateViewId()
            setText(if (choice == ChineseEngineChoice.RIME) R.string.engine_rime_name else R.string.engine_dictionary_name)
            minHeight = dp(48)
        }
    }
    private val engineChoices = RadioGroup(context).apply {
        orientation = LinearLayout.VERTICAL
        engineButtons.values.forEach(::addView)
        setOnCheckedChangeListener { _, id ->
            if (!suppressSwitchCallbacks) engineButtons.entries.firstOrNull { it.value.id == id }?.let { onChineseEngineChanged(it.key) }
        }
    }
    private val dictionaryNotice = TextView(context).apply {
        setText(R.string.engine_dictionary_notice)
        textSize = 13f
        setPadding(0, dp(6), 0, dp(8))
    }

    init {
        isFillViewport = true
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xfffafafa.toInt()))
        addView(content)
        buildContent()
    }

    fun render(state: SettingsScreenState) {
        enabledStatus.setText(if (state.isEnabled) R.string.setting_enabled else R.string.setting_disabled)
        currentStatus.setText(if (state.isCurrent) R.string.setting_current else R.string.setting_not_selected)
        engineStatus.text = state.engineStatus
        aiStatus.text = when {
            !state.aiEnabled -> context.getString(R.string.ai_disabled)
            !state.aiNetworkAllowed -> context.getString(R.string.ai_network_disabled)
            !state.aiKeyConfigured -> context.getString(R.string.ai_key_missing)
            else -> "${state.aiModel} · ${state.aiEndpoint}"
        }
        chineseSettings.render(state.chineseOptions, state.engineCapabilities)
        dictionaryNotice.visibility = if (state.chineseEngine == ChineseEngineChoice.DICTIONARY_TEST) View.VISIBLE else View.GONE
        renderLanguagePacks(state.languagePacks)
        suppressSwitchCallbacks = true
        try {
            engineChoices.check(engineButtons.getValue(state.chineseEngine).id)
            updateSwitch(learningSwitch, state.learningEnabled)
            updateSwitch(modelSwitch, state.experimentalModelRanking)
            updateSwitch(incognitoSwitch, state.incognitoMode)
            updateSwitch(secureClipboardSwitch, state.secureClipboardEnabled)
            updateSwitch(hapticsSwitch, state.hapticsEnabled)
            updateSwitch(associationSwitch, state.wordAssociationsEnabled)
            updateSwitch(aiEnabledSwitch, state.aiEnabled)
            updateSwitch(aiNetworkSwitch, state.aiNetworkAllowed)
        } finally {
            suppressSwitchCallbacks = false
        }
    }

    private fun buildContent() {
        content.addView(TextView(context).apply {
            text = context.getString(R.string.app_name)
            textSize = 28f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        })
        content.addView(TextView(context).apply {
            text = BuildConfig.VERSION_NAME
            textSize = 13f
            alpha = 0.66f
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            }
        })

        section(context.getString(R.string.section_input_method))
        statusRow(context.getString(R.string.setting_system_status), enabledStatus)
        statusRow(context.getString(R.string.setting_current_keyboard), currentStatus)
        commandRow(
            commandButton(context.getString(R.string.setting_enable)) { onEnableRequested() },
            commandButton(context.getString(R.string.setting_switch)) { onSwitchRequested() },
        )

        section(context.getString(R.string.section_privacy))
        content.addView(learningSwitch)
        content.addView(incognitoSwitch)
        content.addView(secureClipboardSwitch)
        command(context.getString(R.string.clipboard_guard_title)) { onClipboardGuardRequested() }

        section(context.getString(R.string.ai_title))
        content.addView(aiEnabledSwitch)
        content.addView(aiNetworkSwitch)
        content.addView(valueText().apply { setText(R.string.ai_privacy_notice) })
        statusRow(context.getString(R.string.ai_configuration), aiStatus)
        command(context.getString(R.string.ai_settings)) { onAiSettingsRequested() }
        command(context.getString(R.string.ai_clear_data)) { onAiDataClearRequested() }

        section(context.getString(R.string.section_data))
        command(context.getString(R.string.setting_user_phrases)) { onDictionaryRequested() }
        command(context.getString(R.string.expression_manager_title)) { onExpressionsRequested() }
        command(context.getString(R.string.clear_personal_data)) { onPersonalDataClearRequested() }
        command(context.getString(R.string.secure_clipboard)) { onSecureClipboardRequested() }

        section(context.getString(R.string.section_language_packs))
        command(context.getString(R.string.setting_import_pack)) { onLanguagePackRequested() }
        content.addView(languagePackContent)

        section(context.getString(R.string.section_input))
        content.addView(hapticsSwitch)
        content.addView(associationSwitch)
        command(context.getString(R.string.keyboard_appearance)) { onAppearanceRequested() }

        section(context.getString(R.string.chinese_input_settings))
        content.addView(engineChoices)
        content.addView(dictionaryNotice)
        command(context.getString(R.string.select_builtin_chinese)) { onBuiltInEngineSelected() }
        content.addView(chineseSettings)
        content.addView(modelSwitch)
        content.addView(TextView(context).apply { setText(R.string.experimental_model_help); textSize = 14f })

        section(context.getString(R.string.section_engine))
        statusRow(context.getString(R.string.language_chinese), engineStatus)
    }

    private fun section(title: String) {
        content.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary, 0xff087f68.toInt()))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
                bottomMargin = dp(6)
            }
        })
        content.addView(divider())
    }

    private fun statusRow(label: String, value: TextView) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value)
        })
    }

    private fun command(label: String, callback: () -> Unit) {
        content.addView(MaterialButton(context).apply {
            text = label
            contentDescription = label
            textSize = 16f
            letterSpacing = 0f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary, Color.BLACK))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(50))
            setOnClickListener { callback() }
        })
    }

    private fun commandRow(vararg buttons: View) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
            buttons.forEach(::addView)
        })
    }

    private fun commandButton(label: String, callback: () -> Unit) = MaterialButton(context).apply {
        text = label
        isAllCaps = false
        letterSpacing = 0f
        cornerRadius = dp(6)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(8)
        }
        setOnClickListener { callback() }
    }

    private fun renderLanguagePacks(packs: List<LanguagePackScreenState>) {
        languagePackContent.removeAllViews()
        if (packs.isEmpty()) {
            languagePackContent.addView(valueText().apply {
                setText(R.string.setting_no_packs)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
            })
            return
        }
        packs.forEach { pack -> languagePackContent.addView(languagePackRow(pack)) }
    }

    private fun languagePackRow(pack: LanguagePackScreenState): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(68))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            addView(TextView(context).apply {
                text = pack.displayName
                textSize = 15f
                maxLines = 1
            })
            addView(TextView(context).apply {
                text = buildString {
                    append(pack.languageTag).append(" · ").append(pack.version)
                    if (!pack.available) append(" · 不可用")
                }
                textSize = 12f
                alpha = 0.66f
                maxLines = 1
            })
        })
        addView(MaterialSwitch(context).apply {
            isChecked = pack.enabled
            isEnabled = true
            contentDescription = "启用 ${pack.displayName}"
            setOnCheckedChangeListener { _, checked -> onLanguagePackEnabledChanged(pack.key, checked) }
        })
        addView(commandButton(if (pack.selected) "使用中" else "使用") {
            if (pack.available) onLanguagePackSelected(pack.key)
        }.apply { isEnabled = pack.available })
        addView(commandButton("删除") { onLanguagePackDeleteRequested(pack.key) })
    }

    private fun settingSwitch(label: String, callback: (Boolean) -> Unit) = MaterialSwitch(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
        setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) callback(checked)
        }
    }

    private fun updateSwitch(view: MaterialSwitch, checked: Boolean) {
        if (view.isChecked != checked) view.isChecked = checked
    }

    private fun valueText() = TextView(context).apply {
        textSize = 14f
        alpha = 0.72f
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant, 0xffd9dddb.toInt()))
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return values.getColor(0, fallback).also { values.recycle() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
