package dev.zeroinput.ime.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardMaterial
import dev.zeroinput.ime.ui.KeyboardTheme
import dev.zeroinput.ime.ui.ExpandedCandidateDensity
import dev.zeroinput.ime.ui.ExpandedCandidateLayout
import dev.zeroinput.ime.ui.R

/** Controls emit immutable appearance values; persistence and image access belong to the Activity. */
internal class AppearanceOptionsView(
    context: Context,
    initial: KeyboardAppearance,
    private val changed: (KeyboardAppearance, Boolean) -> Unit,
    private val chooseImage: () -> Unit,
    private val removeImage: () -> Unit,
    private val reset: () -> Unit,
    initialSection: AppearanceSection = AppearanceSection.STYLE,
    private val sectionChanged: (AppearanceSection) -> Unit = {},
) : LinearLayout(context) {
    constructor(context: Context) : this(context, KeyboardAppearance(), { _, _ -> }, {}, {}, {})
    private var value = initial

    private var selectedSection = initialSection

    init {
        orientation = VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
        renderSection()
    }

    private fun renderSection() {
        removeAllViews()
        AppearanceSection.entries.chunked(2).forEach { row ->
            addView(LinearLayout(context).apply {
                isBaselineAligned = false
                row.forEach { section ->
                    addView(MaterialButton(context).apply {
                        setText(section.label)
                        isAllCaps = false
                        setSingleLine(false)
                        ellipsize = null
                        isCheckable = true
                        isChecked = selectedSection == section
                        applySettingsModeStyle()
                        cornerRadius = dp(8)
                        minHeight = dp(48)
                        setOnClickListener {
                            if (selectedSection == section) {
                                isChecked = true
                                return@setOnClickListener
                            }
                            selectedSection = section
                            sectionChanged(section)
                            renderSection()
                        }
                    }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            })
        }
        when (selectedSection) {
            AppearanceSection.STYLE -> styleOptions()
            AppearanceSection.KEYS -> keyOptions(value)
            AppearanceSection.CANDIDATES -> candidateOptions(value)
            AppearanceSection.BACKGROUND -> backgroundOptions(value)
        }
        button(R.string.appearance_reset, reset)
    }

    private fun styleOptions() {
        heading(dev.zeroinput.ime.R.string.appearance_presets)
        description(dev.zeroinput.ime.R.string.appearance_presets_hint)
        KeyboardTheme.entries.chunked(2).forEach { row ->
            addView(LinearLayout(context).apply {
                isBaselineAligned = false
                row.forEach { theme ->
                    addView(MaterialButton(context).apply {
                        setText(theme.label)
                        isAllCaps = false
                        setSingleLine(false)
                        ellipsize = null
                        isCheckable = true
                        isChecked = KeyboardStylePresets.apply(value, theme) == value
                        applySettingsModeStyle()
                        cornerRadius = dp(8)
                        minHeight = dp(56)
                        val themed = KeyboardThemeContext.create(context, theme)
                        val colors = themed.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
                        val swatch = colors.getColor(0, android.graphics.Color.GRAY)
                        colors.recycle()
                        icon = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(swatch)
                            setSize(dp(18), dp(18))
                        }
                        iconTint = null
                        setOnClickListener {
                            update(KeyboardStylePresets.apply(value, theme))
                            renderSection()
                        }
                    }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            })
        }
        group(R.string.appearance_palette, KeyboardTheme.entries.map { it.label }, value.theme.ordinal) {
            update(value.copy(theme = KeyboardTheme.entries[it]))
            renderSection()
        }
        group(R.string.appearance_material, KeyboardMaterial.entries.map { it.label }, value.material.ordinal) {
            update(value.copy(material = KeyboardMaterial.entries[it]))
            renderSection()
        }
    }

    private fun keyOptions(initial: KeyboardAppearance) {
        description(R.string.appearance_classic_hint)
        addView(SwitchMaterial(context).apply {
            setText(R.string.appearance_borders)
            minHeight = dp(48)
            isChecked = initial.borders
            setOnCheckedChangeListener { _, enabled -> update(value.copy(borders = enabled)) }
        })
        slider(R.string.appearance_radius, initial.cornerRadius, 20) { number, commit -> update(value.copy(cornerRadius = number), commit) }
        slider(R.string.appearance_spacing, initial.keySpacing, 6) { number, commit -> update(value.copy(keySpacing = number), commit) }
        group(dev.zeroinput.ime.R.string.keyboard_height, KeyboardHeight.entries.map { it.label }, initial.height.ordinal) {
            update(value.copy(height = KeyboardHeight.entries[it]))
        }
    }

    private fun backgroundOptions(initial: KeyboardAppearance) {
        group(R.string.appearance_background, KeyboardBackground.entries.map { it.label }, initial.background.ordinal) {
            val mode = KeyboardBackground.entries[it]
            update(value.copy(background = mode))
            if (mode == KeyboardBackground.IMAGE && value.imageRevision.isEmpty()) chooseImage()
        }
        val colors = listOf(null, 0xffbed5ef.toInt(), 0xff343c49.toInt(), 0xffe8d8bc.toInt(), 0xffebcfdb.toInt())
        group(R.string.appearance_color, listOf(R.string.appearance_color_auto, R.string.appearance_color_blue,
            R.string.appearance_color_gray, R.string.appearance_color_sand, R.string.appearance_color_rose),
            colors.indexOf(initial.backgroundColor)) {
            update(value.copy(backgroundColor = colors[it]))
            renderSection()
        }
        val customColor = dev.zeroinput.ime.R.string.appearance_custom_background_color
        addView(MaterialButton(context).apply {
            isAllCaps = false
            minHeight = dp(48)
            text = if (initial.backgroundColor in colors) context.getString(customColor) else
                context.getString(dev.zeroinput.ime.R.string.appearance_custom_background_color_value,
                    initial.backgroundColor?.and(0xFFFFFF) ?: 0)
            setOnClickListener {
                val themed = KeyboardThemeContext.create(context, value.theme)
                val color = value.backgroundColor ?: com.google.android.material.color.MaterialColors.getColor(
                    themed, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
                showKeyboardBackgroundColorPicker(context, color) {
                    update(value.copy(backgroundColor = it))
                    renderSection()
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        button(R.string.appearance_choose_image, chooseImage)
        button(R.string.appearance_remove_image, removeImage)
        description(R.string.appearance_image_hint)
        slider(R.string.appearance_opacity, initial.backgroundOpacity, 100) { number, commit -> update(value.copy(backgroundOpacity = number), commit) }
        description(R.string.appearance_opacity_hint)
        slider(R.string.appearance_dim, initial.backgroundDim, 80) { number, commit -> update(value.copy(backgroundDim = number), commit) }
        slider(R.string.appearance_blur, initial.backgroundBlur, 20) { number, commit -> update(value.copy(backgroundBlur = number), commit) }
        description(R.string.appearance_glass_hint)
    }

    private fun update(updated: KeyboardAppearance, commit: Boolean = true) {
        value = updated.sanitized()
        changed(value, commit)
    }

    private fun candidateOptions(initial: KeyboardAppearance) {
        heading(R.string.expanded_candidates_heading)
        group(R.string.expanded_candidates_layout, ExpandedCandidateLayout.entries.map { it.label },
            initial.expandedCandidates.layout.ordinal) {
            update(value.copy(expandedCandidates = value.expandedCandidates.copy(layout = ExpandedCandidateLayout.entries[it])))
        }
        group(R.string.expanded_candidates_density, ExpandedCandidateDensity.entries.map { it.label },
            initial.expandedCandidates.density.ordinal) {
            update(value.copy(expandedCandidates = value.expandedCandidates.copy(density = ExpandedCandidateDensity.entries[it])))
        }
        slider(R.string.expanded_candidates_text_size, initial.expandedCandidates.textSize, 24, 16) { size, commit ->
            update(value.copy(expandedCandidates = value.expandedCandidates.copy(textSize = size)), commit)
        }
        addView(SwitchMaterial(context).apply {
            setText(R.string.expanded_candidates_separators)
            minHeight = dp(48)
            isChecked = initial.expandedCandidates.separators
            setOnCheckedChangeListener { _, enabled ->
                update(value.copy(expandedCandidates = value.expandedCandidates.copy(separators = enabled)))
            }
        })
    }

    private fun group(title: Int, labels: List<Int>, selected: Int, onSelected: (Int) -> Unit) {
        heading(title)
        val buttons = mutableListOf<MaterialButton>()
        labels.withIndex().chunked(2).forEach { row ->
            addView(LinearLayout(context).apply {
                isBaselineAligned = false
                row.forEach { (index, label) ->
                    val button = MaterialButton(context).apply {
                        setText(label)
                        isAllCaps = false
                        setSingleLine(false)
                        ellipsize = null
                        isCheckable = true
                        isChecked = index == selected
                        applySettingsModeStyle()
                        cornerRadius = dp(8)
                        minHeight = dp(48)
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        setOnClickListener {
                            buttons.forEachIndexed { buttonIndex, other -> other.isChecked = buttonIndex == index }
                            onSelected(index)
                        }
                    }
                    buttons += button
                    addView(button, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            })
        }
    }

    private fun slider(label: Int, initial: Int, limit: Int, minimum: Int = 0, onValue: (Int, Boolean) -> Unit) {
        var tracking = false
        val text = TextView(context).apply { text = context.getString(label, initial); setPadding(0, dp(12), 0, 0) }
        addView(text)
        addView(SeekBar(context).apply {
            max = limit
            min = minimum
            progress = initial
            contentDescription = text.text
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, number: Int, fromUser: Boolean) {
                    text.text = context.getString(label, number)
                    bar.contentDescription = text.text
                    if (fromUser) onValue(number, !tracking)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
                override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; onValue(bar.progress, true) }
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun heading(label: Int) = addView(TextView(context).apply {
        setText(label); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        setPadding(0, dp(20), 0, dp(4))
    })

    private fun description(label: Int) = addView(TextView(context).apply { setText(label); setPadding(0, dp(4), 0, dp(8)) })
    private fun button(label: Int, clicked: () -> Unit) = addView(MaterialButton(context).apply {
        setText(label); minHeight = dp(48); setOnClickListener { clicked() }
    }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

enum class AppearanceSection(val label: Int) {
    STYLE(dev.zeroinput.ime.R.string.appearance_section_style),
    KEYS(dev.zeroinput.ime.R.string.appearance_section_keys),
    CANDIDATES(R.string.expanded_candidates_heading),
    BACKGROUND(R.string.appearance_background),
}
