package dev.zeroinput.ime.settings

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.BuildConfig
import dev.zeroinput.ime.R
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EngineCapability

data class SettingsScreenState(
    val isEnabled: Boolean,
    val isCurrent: Boolean,
    val learningEnabled: Boolean,
    val incognitoMode: Boolean,
    val secureClipboardEnabled: Boolean,
    val hapticsEnabled: Boolean,
    val soundEffectsEnabled: Boolean = false,
    val engineStatus: String,
    val chineseOptions: ChineseInputOptions = ChineseInputOptions(),
    val experimentalModelRanking: Boolean = false,
    val wordAssociationsEnabled: Boolean = true,
    val pairedSymbolsEnabled: Boolean = true,
    val languagePacks: List<LanguagePackScreenState> = emptyList(),
    val engineCapabilities: Set<EngineCapability> = EngineCapability.entries.toSet(),
    val aiEnabled: Boolean = false,
    val aiNetworkAllowed: Boolean = false,
    val aiEndpoint: String = "",
    val aiModel: String = "",
    val aiProviderName: String = "",
    val aiKeyConfigured: Boolean = false,
    val aiSaveConversations: Boolean = false,
    val aiThinkingVisible: Boolean = true,
    val interfaceLanguageTag: String = "",
    val learnFromCommittedText: Boolean = false,
    val personalPromotionThreshold: Int = 1,
    val privateDraftEnabled: Boolean = false,
    val savePrivateDraftOnSubmit: Boolean = true,
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

class SettingsScreenView(context: Context) : LinearLayout(context) {
    var onEnableRequested: () -> Unit = {}
    var onSwitchRequested: () -> Unit = {}
    var onLearningChanged: (Boolean) -> Unit = {}
    var onLearnFromCommittedTextChanged: (Boolean) -> Unit = {}
    var onPersonalPromotionThresholdRequested: () -> Unit = {}
    var onIncognitoChanged: (Boolean) -> Unit = {}
    var onSecureClipboardChanged: (Boolean) -> Unit = {}
    var onPrivateDraftChanged: (Boolean) -> Unit = {}
    var onPrivateDraftCopyChanged: (Boolean) -> Unit = {}
    var onHapticsChanged: (Boolean) -> Unit = {}
    var onSoundEffectsChanged: (Boolean) -> Unit = {}
    var onWordAssociationsChanged: (Boolean) -> Unit = {}
    var onPairedSymbolsChanged: (Boolean) -> Unit = {}
    var onDictionaryRequested: () -> Unit = {}
    var onPublicDictionaryRequested: () -> Unit = {}
    var onExpressionsRequested: () -> Unit = {}
    var onAppearanceRequested: () -> Unit = {}
    var onPersonalDataClearRequested: () -> Unit = {}
    var onSecureClipboardRequested: () -> Unit = {}
    var onSecureClipboardClearRequested: () -> Unit = {}
    var onClipboardGuardRequested: () -> Unit = {}
    var onLanguagePackRequested: () -> Unit = {}
    var onLanguagePackEnabledChanged: (String, Boolean) -> Unit = { _, _ -> }
    var onLanguagePackSelected: (String) -> Unit = {}
    var onLanguagePackDeleteRequested: (String) -> Unit = {}
    var onChineseOptionsChanged: (ChineseInputOptions) -> Unit = {}
    var onModelRankingChanged: (Boolean) -> Unit = {}
    var onBuiltInEngineSelected: () -> Unit = {}
    var onAiEnabledChanged: (Boolean) -> Unit = {}
    var onAiNetworkChanged: (Boolean) -> Unit = {}
    var onAiThinkingVisibleChanged: (Boolean) -> Unit = {}
    var onAiSettingsRequested: () -> Unit = {}
    var onAiAssistantRequested: () -> Unit = {}
    var onAiDataClearRequested: () -> Unit = {}
    var onAiPageSettingsRequested: () -> Unit = {}
    var onAiDeviceAgentSettingsRequested: () -> Unit = {}
    var onInterfaceLanguageChanged: (String) -> Unit = {}

    private var suppressSwitchCallbacks = false
    private var aiControlsEnabled = true
    private val pages = mutableMapOf<SettingsCategory, LinearLayout>()
    private val positions = mutableMapOf<SettingsCategory, Int>()
    private val scroll = ScrollView(context).apply { isFillViewport = true }
    private val toolbar = com.google.android.material.appbar.MaterialToolbar(context)
    private val pageTitle = TextView(context).apply {
        textSize = 22f
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        setPadding(0, dp(12), 0, dp(12))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    private var content = newPage()
    var category: SettingsCategory = SettingsCategory.HOME
        private set
    var onCategoryChanged: (SettingsCategory) -> Unit = {}
    private val homeStatus = valueText()

    private fun newPage() = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(32))
    }

    private fun page(category: SettingsCategory) {
        content = newPage()
        pages[category] = content
    }

    fun navigateTo(target: SettingsCategory) {
        positions[category] = scroll.scrollY
        showCategory(target)
    }

    private fun showCategory(target: SettingsCategory) {
        category = target
        scroll.removeAllViews()
        scroll.addView(pages.getValue(target))
        pageTitle.setText(target.title)
        toolbar.navigationIcon = if (target == SettingsCategory.HOME) null
            else androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_arrow_back)
        toolbar.navigationContentDescription = context.getString(R.string.navigate_up)
        val position = positions[target] ?: 0
        scroll.post { if (category == target) scroll.scrollTo(0, position) }
        onCategoryChanged(target)
    }

    fun saveNavigation(state: android.os.Bundle) {
        positions[category] = scroll.scrollY
        state.putString("settings.category", category.name)
        positions.forEach { (key, value) -> state.putInt("settings.scroll.${key.name}", value) }
    }

    fun restoreNavigation(state: android.os.Bundle?) {
        if (state == null) return
        SettingsCategory.entries.forEach { positions[it] = state.getInt("settings.scroll.${it.name}", 0) }
        showCategory(SettingsCategory.entries.firstOrNull { it.name == state.getString("settings.category") }
            ?: SettingsCategory.HOME)
    }

    private val enabledStatus = valueText()
    private val currentStatus = valueText()
    private val engineStatus = valueText()
    private val chineseSettings = ChineseSettingsView(context).apply {
        onOptionsChanged = { onChineseOptionsChanged(it) }
    }

    fun showFuzzySettings() { navigateTo(SettingsCategory.INPUT); chineseSettings.showFuzzySettings() }

    /** Prevents a second AI mutation from racing the encrypted settings write. */
    fun setAiControlsEnabled(enabled: Boolean) {
        aiControlsEnabled = enabled
        aiEnabledSwitch.isEnabled = enabled
        aiNetworkSwitch.isEnabled = enabled && aiEnabledSwitch.isChecked
    }

    private val languagePackContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val learningSwitch = settingSwitch(context.getString(R.string.setting_learning)) { onLearningChanged(it) }
    private val committedLearningSwitch = settingSwitch(context.getString(R.string.setting_learn_from_committed_text)) {
        onLearnFromCommittedTextChanged(it)
    }
    private lateinit var promotionThreshold: MaterialButton
    private val modelSwitch = settingSwitch(context.getString(R.string.experimental_model_ranking)) { onModelRankingChanged(it) }
    private val incognitoSwitch = settingSwitch(context.getString(R.string.setting_incognito)) { onIncognitoChanged(it) }
    private val secureClipboardSwitch = settingSwitch(context.getString(R.string.secure_clipboard)) { onSecureClipboardChanged(it) }
    private val privateDraftSwitch = settingSwitch(context.getString(R.string.private_draft_default)) { onPrivateDraftChanged(it) }
    private val privateDraftCopySwitch = settingSwitch(context.getString(R.string.private_draft_keep_copy)) { onPrivateDraftCopyChanged(it) }
    private val hapticsSwitch = settingSwitch(context.getString(R.string.setting_haptics)) { onHapticsChanged(it) }
    private val soundEffectsSwitch = settingSwitch(context.getString(R.string.setting_sound_effects)) { onSoundEffectsChanged(it) }
    private val associationSwitch = settingSwitch(context.getString(R.string.setting_word_associations)) { onWordAssociationsChanged(it) }
    private val pairedSymbolsSwitch = settingSwitch(context.getString(R.string.setting_paired_symbols)) { onPairedSymbolsChanged(it) }
    private val aiEnabledSwitch = settingSwitch(context.getString(R.string.ai_enabled)) { onAiEnabledChanged(it) }
    private val aiNetworkSwitch = settingSwitch(context.getString(R.string.ai_network)) { onAiNetworkChanged(it) }
    private val aiThinkingSwitch = settingSwitch(context.getString(dev.zeroinput.ime.ui.R.string.ai_show_thinking)) { onAiThinkingVisibleChanged(it) }
    private val aiStatus = valueText()
    private val interfaceLanguage = com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
        isSingleSelection = true
        isSelectionRequired = true
        addWrappingModeButton(languageChoice("system", R.string.language_follow_system))
        addWrappingModeButton(languageChoice("zh", R.string.language_simplified_chinese))
        addWrappingModeButton(languageChoice("en", R.string.language_english))
        addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !suppressSwitchCallbacks) {
                val value = findViewById<MaterialButton>(checkedId).tag as String
                onInterfaceLanguageChanged(value)
            }
        }
    }

    init {
        orientation = VERTICAL
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xfffafafa.toInt()))
        toolbar.addView(pageTitle, androidx.appcompat.widget.Toolbar.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        toolbar.setNavigationOnClickListener { navigateTo(SettingsCategory.HOME) }
        buildContent()
        showCategory(SettingsCategory.HOME)
    }

    fun render(state: SettingsScreenState) {
        enabledStatus.setText(if (state.isEnabled) R.string.setting_enabled else R.string.setting_disabled)
        currentStatus.setText(if (state.isCurrent) R.string.setting_current else R.string.setting_not_selected)
        homeStatus.setText(if (state.isCurrent) R.string.setting_current else if (state.isEnabled)
            R.string.setting_not_selected else R.string.setting_disabled)
        engineStatus.text = state.engineStatus
        promotionThreshold.text = context.getString(R.string.user_dictionary_threshold_value, state.personalPromotionThreshold)
        promotionThreshold.contentDescription = promotionThreshold.text
        aiStatus.text = when {
            !state.aiEnabled -> context.getString(R.string.ai_disabled)
            !state.aiNetworkAllowed -> context.getString(R.string.ai_network_disabled)
            !state.aiKeyConfigured -> context.getString(R.string.ai_key_missing)
            else -> listOf(state.aiProviderName, state.aiModel).filter(String::isNotBlank).joinToString(" · ")
        }
        chineseSettings.render(state.chineseOptions, state.engineCapabilities)
        renderLanguagePacks(state.languagePacks)
        suppressSwitchCallbacks = true
        try {
            val language = state.interfaceLanguageTag.ifBlank { "system" }
            (0 until interfaceLanguage.childCount).asSequence()
                .map(interfaceLanguage::getChildAt)
                .filterIsInstance<MaterialButton>()
                .firstOrNull { it.tag == language }?.let { interfaceLanguage.check(it.id) }
            updateSwitch(learningSwitch, state.learningEnabled)
            updateSwitch(committedLearningSwitch, state.learnFromCommittedText)
            updateSwitch(modelSwitch, state.experimentalModelRanking)
            updateSwitch(incognitoSwitch, state.incognitoMode)
            updateSwitch(secureClipboardSwitch, state.secureClipboardEnabled)
            updateSwitch(privateDraftSwitch, state.privateDraftEnabled)
            updateSwitch(privateDraftCopySwitch, state.savePrivateDraftOnSubmit)
            updateSwitch(hapticsSwitch, state.hapticsEnabled)
            updateSwitch(soundEffectsSwitch, state.soundEffectsEnabled)
            updateSwitch(associationSwitch, state.wordAssociationsEnabled)
            updateSwitch(pairedSymbolsSwitch, state.pairedSymbolsEnabled)
            updateSwitch(aiEnabledSwitch, state.aiEnabled)
            updateSwitch(aiNetworkSwitch, state.aiEnabled && state.aiNetworkAllowed)
            updateSwitch(aiThinkingSwitch, state.aiThinkingVisible)
            aiNetworkSwitch.isEnabled = aiControlsEnabled && aiEnabledSwitch.isChecked
        } finally {
            suppressSwitchCallbacks = false
        }
    }

    private fun buildContent() {
        buildHome()
        page(SettingsCategory.GENERAL)
        content.addView(valueText().apply { text = BuildConfig.VERSION_NAME; gravity = Gravity.START })
        section(context.getString(R.string.section_input_method))
        content.addView(TextView(context).apply {
            setText(R.string.interface_language)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(4))
        })
        content.addView(interfaceLanguage)
        statusRow(context.getString(R.string.setting_system_status), enabledStatus)
        statusRow(context.getString(R.string.setting_current_keyboard), currentStatus)
        commandRow(
            commandButton(context.getString(R.string.setting_enable)) { onEnableRequested() },
            commandButton(context.getString(R.string.setting_switch)) { onSwitchRequested() },
        )

        page(SettingsCategory.PRIVACY)
        section(context.getString(R.string.section_privacy))
        content.addView(learningSwitch)
        content.addView(committedLearningSwitch)
        promotionThreshold = command(context.getString(R.string.user_dictionary_threshold)) { onPersonalPromotionThresholdRequested() }
        content.addView(incognitoSwitch)
        content.addView(privateDraftSwitch)
        content.addView(privateDraftCopySwitch)
        content.addView(secureClipboardSwitch)
        command(context.getString(R.string.clipboard_guard_title)) { onClipboardGuardRequested() }

        command(context.getString(R.string.secure_clipboard)) { onSecureClipboardRequested() }
        command(context.getString(R.string.clear_personal_data)) { onPersonalDataClearRequested() }
        command(context.getString(R.string.clear_secure_clipboard)) { onSecureClipboardClearRequested() }

        page(SettingsCategory.AI)
        section(context.getString(R.string.ai_title))
        content.addView(aiEnabledSwitch)
        content.addView(aiNetworkSwitch)
        content.addView(aiThinkingSwitch)
        content.addView(valueText().apply { setText(R.string.ai_privacy_notice) })
        statusRow(context.getString(R.string.ai_configuration), aiStatus)
        command(context.getString(R.string.ai_settings)) { onAiSettingsRequested() }
        command(context.getString(R.string.ai_assistant_open)) { onAiAssistantRequested() }
        command(context.getString(R.string.ai_page_access_title)) { onAiPageSettingsRequested() }
        command(context.getString(R.string.ai_device_agent_settings)) { onAiDeviceAgentSettingsRequested() }
        command(context.getString(R.string.ai_clear_data)) { onAiDataClearRequested() }

        page(SettingsCategory.DICTIONARIES)
        section(context.getString(R.string.section_data))
        command(context.getString(R.string.setting_user_phrases)) { onDictionaryRequested() }
        command(context.getString(R.string.public_dictionary_title)) { onPublicDictionaryRequested() }
        command(context.getString(R.string.expression_manager_title)) { onExpressionsRequested() }
        section(context.getString(R.string.section_language_packs))
        command(context.getString(R.string.setting_import_pack)) { onLanguagePackRequested() }
        content.addView(languagePackContent)

        page(SettingsCategory.INPUT)
        section(context.getString(R.string.section_input))
        content.addView(hapticsSwitch)
        content.addView(soundEffectsSwitch)
        content.addView(associationSwitch)
        content.addView(pairedSymbolsSwitch)

        section(context.getString(R.string.chinese_input_settings))
        command(context.getString(R.string.select_builtin_chinese)) { onBuiltInEngineSelected() }
        content.addView(chineseSettings)
        content.addView(modelSwitch)
        content.addView(TextView(context).apply { setText(R.string.experimental_model_help); textSize = 14f })

        content = pages.getValue(SettingsCategory.GENERAL)
        section(context.getString(R.string.section_engine))
        statusRow(context.getString(R.string.language_chinese), engineStatus)
    }

    private fun section(title: String) {
        val header = TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary, 0xff087f68.toInt()))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
                bottomMargin = dp(6)
            }
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        content.addView(header)
        content.addView(divider())
    }

    private fun buildHome() {
        page(SettingsCategory.HOME)
        statusRow(context.getString(R.string.setting_current_keyboard), homeStatus)
        commandRow(
            commandButton(context.getString(R.string.setting_enable)) { onEnableRequested() },
            commandButton(context.getString(R.string.setting_switch)) { onSwitchRequested() },
        )
        SettingsCategory.entries.filter { it != SettingsCategory.HOME }.forEach { destination ->
            categoryRow(destination.title, destination.summary) { navigateTo(destination) }
            if (destination == SettingsCategory.INPUT) {
                categoryRow(R.string.keyboard_appearance, R.string.settings_appearance_summary) { onAppearanceRequested() }
            }
        }
    }

    private fun categoryRow(title: Int, summary: Int, action: () -> Unit) {
        content.addView(object : LinearLayout(context) {
            override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
        }.apply {
            tag = title
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            minimumHeight = dp(88)
            setPadding(0, dp(16), 0, dp(16))
            contentDescription = context.getString(title) + ", " + context.getString(summary)
            context.withStyledAttributes(attrs = intArrayOf(android.R.attr.selectableItemBackground)) {
                background = getDrawable(0)
            }
            setOnClickListener { action() }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(TextView(context).apply {
                    setText(title)
                    textSize = 18f
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
                })
                addView(valueText().apply {
                    setText(summary)
                    gravity = Gravity.START
                    setPadding(0, dp(8), 0, 0)
                })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(divider())
    }

    private fun statusRow(label: String, value: TextView) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun command(label: String, callback: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = label
            contentDescription = label
            textSize = 14f
            letterSpacing = 0f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minWidth = 0
            minimumWidth = 0
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary, Color.BLACK))
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { callback() }
        }.also(content::addView)

    private fun commandRow(vararg buttons: View) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            buttons.forEach(::addView)
        })
    }

    private fun commandButton(label: String, callback: () -> Unit) = MaterialButton(context).apply {
        text = label
        isAllCaps = false
        letterSpacing = 0f
        cornerRadius = dp(6)
        minHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
        setOnClickListener { callback() }
    }

    private fun languageChoice(tag: String, label: Int) = MaterialButton(context).apply {
        id = View.generateViewId()
        this.tag = tag
        setText(label)
        isAllCaps = false
        isCheckable = true
        applySettingsModeStyle()
        setPadding(dp(2), 0, dp(2), 0)
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun renderLanguagePacks(packs: List<LanguagePackScreenState>) {
        languagePackContent.removeAllViews()
        if (packs.isEmpty()) {
            languagePackContent.addView(valueText().apply {
                setText(R.string.setting_no_packs)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                minHeight = dp(48)
            })
            return
        }
        packs.forEach { pack -> languagePackContent.addView(languagePackRow(pack)) }
    }

    private fun languagePackRow(pack: LanguagePackScreenState): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(8), 0, dp(12))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(MaterialSwitch(context).apply {
            text = pack.displayName
            textSize = 16f
            minHeight = dp(48)
            setSingleLine(false)
            isChecked = pack.enabled
            contentDescription = context.getString(R.string.language_pack_enable, pack.displayName)
            setOnCheckedChangeListener { _, checked -> onLanguagePackEnabledChanged(pack.key, checked) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(valueText().apply {
            gravity = Gravity.START
            text = buildString {
                append(pack.languageTag).append(" · ").append(pack.version)
                if (!pack.available) append(" · ").append(context.getString(R.string.language_pack_unavailable))
            }
        })
        addView(LinearLayout(context).apply {
            isBaselineAligned = false
            val use = commandButton(context.getString(if (pack.selected) R.string.language_pack_in_use else R.string.language_pack_use)) {
                if (pack.available) onLanguagePackSelected(pack.key)
            }.apply { isEnabled = pack.available }
            val remove = commandButton(context.getString(R.string.language_pack_delete)) { onLanguagePackDeleteRequested(pack.key) }
            listOf(use, remove).forEach { button ->
                button.minWidth = 0
                button.minimumWidth = 0
                addView(button, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        })
    }

    private fun settingSwitch(label: String, callback: (Boolean) -> Unit) = MaterialSwitch(context).apply {
        text = label
        textSize = 16f
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
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
