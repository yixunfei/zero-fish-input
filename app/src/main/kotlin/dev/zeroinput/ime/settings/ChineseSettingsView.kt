package dev.zeroinput.ime.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.color.MaterialColors
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.EngineCapability
import dev.zeroinput.engine.api.FuzzyPinyinPair
import dev.zeroinput.ime.R

internal class ChineseSettingsView(context: Context) : LinearLayout(context) {
    var onOptionsChanged: (ChineseInputOptions) -> Unit = {}
    private var options = ChineseInputOptions()
    private var rendering = false
    private val scriptButtons = mapOf(
        ChineseScript.SIMPLIFIED to modeButton(context.getString(R.string.script_simplified)),
        ChineseScript.TRADITIONAL to modeButton(context.getString(R.string.script_traditional)),
    )
    private val scripts = modeGroup(scriptButtons.values.toList()) { id ->
        scriptButtons.entries.firstOrNull { it.value.id == id }?.let { update(options.copy(script = it.key)) }
    }
    private val abbreviated = optionSwitch(R.string.abbreviated_pinyin) { update(options.copy(abbreviatedPinyin = it)) }
    private val punctuation = optionSwitch(R.string.chinese_punctuation) { update(options.copy(chinesePunctuation = it)) }
    private val typoCorrection = optionSwitch(R.string.experimental_typo_correction) {
        update(options.copy(experimentalTypoCorrection = it))
    }
    private val layoutButtons = mapOf(
        ChineseKeyboardLayout.FULL to modeButton(context.getString(R.string.keyboard_full)),
        ChineseKeyboardLayout.NINE_KEY to modeButton(context.getString(R.string.keyboard_nine)),
    )
    private val layouts = modeGroup(layoutButtons.values.toList()) { id ->
        layoutButtons.entries.firstOrNull { it.value.id == id }?.let { update(options.copy(keyboardLayout = it.key)) }
    }
    private val pageButtons = ChineseInputOptions.PAGE_SIZES.associateWith { modeButton(it.toString()) }
    private val pages = modeGroup(pageButtons.values.toList()) { id ->
        pageButtons.entries.firstOrNull { it.value.id == id }?.let { update(options.copy(candidatePageSize = it.key)) }
    }
    private val fuzzySwitches = FuzzyPinyinPair.entries.associateWith { pair ->
        optionSwitch(fuzzyLabel(pair)) { update(options.withFuzzy(pair, it)) }
    }
    private val fuzzyEnabled = optionSwitch(R.string.fuzzy_pinyin_enabled) {
        update(options.copy(fuzzyPinyinEnabled = it))
    }

    init {
        orientation = VERTICAL
        label(R.string.keyboard_layout)
        addView(layouts)
        label(R.string.chinese_script)
        addView(scripts)
        addView(abbreviated)
        addView(punctuation)
        addView(typoCorrection)
        label(R.string.experimental_typo_help)
        label(R.string.candidate_page_size)
        addView(pages)
        label(R.string.fuzzy_pinyin)
        addView(fuzzyEnabled)
        label(R.string.fuzzy_pinyin_enabled_help)
        fuzzySwitches.values.forEach(::addView)
    }

    fun render(value: ChineseInputOptions, capabilities: Set<EngineCapability>) {
        options = value
        rendering = true
        try {
            scripts.check(scriptButtons.getValue(value.script).id)
            layouts.check(layoutButtons.getValue(value.keyboardLayout).id)
            abbreviated.isChecked = value.abbreviatedPinyin
            punctuation.isChecked = value.chinesePunctuation
            typoCorrection.isChecked = value.experimentalTypoCorrection
            typoCorrection.isEnabled = EngineCapability.TYPO_CORRECTION in capabilities && value.keyboardLayout == ChineseKeyboardLayout.FULL
            pages.check(pageButtons.getValue(value.candidatePageSize).id)
            fuzzyEnabled.isChecked = value.fuzzyPinyinEnabled
            fuzzyEnabled.isEnabled = EngineCapability.FUZZY_PINYIN in capabilities
            fuzzySwitches.forEach { (pair, view) -> view.isChecked = value.isFuzzySelected(pair) }
            scriptButtons.values.forEach { it.isEnabled = EngineCapability.CHINESE_SCRIPT in capabilities }
            layoutButtons.values.forEach { it.isEnabled = EngineCapability.NINE_KEY_PINYIN in capabilities }
            abbreviated.isEnabled = EngineCapability.ABBREVIATED_PINYIN in capabilities
            punctuation.isEnabled = EngineCapability.PUNCTUATION_MODE in capabilities
            pageButtons.values.forEach { it.isEnabled = EngineCapability.CANDIDATE_PAGE_SIZE in capabilities }
            fuzzySwitches.values.forEach { it.isEnabled = EngineCapability.FUZZY_PINYIN in capabilities }
        } finally {
            rendering = false
        }
    }

    private fun update(value: ChineseInputOptions) {
        if (rendering || value == options) return
        options = value
        onOptionsChanged(value)
    }

    fun showFuzzySettings() {
        fuzzyEnabled.requestRectangleOnScreen(android.graphics.Rect(0, 0, fuzzyEnabled.width, fuzzyEnabled.height), true)
    }

    private fun modeButton(label: String) = MaterialButton(context).apply {
        id = View.generateViewId()
        text = label
        contentDescription = label
        isAllCaps = false
        letterSpacing = 0f
        minWidth = 0
        minimumWidth = 0
        textSize = 14f
        cornerRadius = dp(6)
        strokeWidth = dp(1)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(
            color(com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY),
            color(com.google.android.material.R.attr.colorSurface, Color.WHITE),
        ))
        setTextColor(ColorStateList(states, intArrayOf(
            color(com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK),
            color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK),
        )))
        strokeColor = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOutline, Color.GRAY))
        layoutParams = LayoutParams(0, dp(48), 1f)
    }

    private fun modeGroup(buttons: List<MaterialButton>, select: (Int) -> Unit) = MaterialButtonToggleGroup(context).apply {
        isSingleSelection = true
        isSelectionRequired = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
        buttons.forEach(::addView)
        addOnButtonCheckedListener { _, id, checked -> if (checked && !rendering) select(id) }
    }

    private fun optionSwitch(label: Int, select: (Boolean) -> Unit) = MaterialSwitch(context).apply {
        setText(label)
        textSize = 16f
        minHeight = dp(48)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setOnCheckedChangeListener { _, checked -> if (!rendering) select(checked) }
    }

    private fun label(resource: Int) {
        addView(TextView(context).apply {
            setText(resource)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun fuzzyLabel(pair: FuzzyPinyinPair): Int = when (pair) {
        FuzzyPinyinPair.Z_ZH -> R.string.fuzzy_z_zh
        FuzzyPinyinPair.C_CH -> R.string.fuzzy_c_ch
        FuzzyPinyinPair.S_SH -> R.string.fuzzy_s_sh
        FuzzyPinyinPair.N_L -> R.string.fuzzy_n_l
        FuzzyPinyinPair.HU_FU -> R.string.fuzzy_hu_fu
        FuzzyPinyinPair.AN_ANG -> R.string.fuzzy_an_ang
        FuzzyPinyinPair.EN_ENG -> R.string.fuzzy_en_eng
        FuzzyPinyinPair.IN_ING -> R.string.fuzzy_in_ing
        FuzzyPinyinPair.R_L -> R.string.fuzzy_r_l
        FuzzyPinyinPair.H_F -> R.string.fuzzy_h_f
        FuzzyPinyinPair.IAN_IANG -> R.string.fuzzy_ian_iang
        FuzzyPinyinPair.UAN_UANG -> R.string.fuzzy_uan_uang
        FuzzyPinyinPair.ON_ONG -> R.string.fuzzy_on_ong
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun color(attribute: Int, fallback: Int): Int = MaterialColors.getColor(context, attribute, fallback)
}
