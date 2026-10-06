package dev.zeroinput.ime.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardMaterial
import dev.zeroinput.ime.ui.KeyboardTheme
import dev.zeroinput.ime.ui.R

/** Controls emit immutable appearance values; persistence and image access belong to the Activity. */
internal class AppearanceOptionsView(
    context: Context,
    initial: KeyboardAppearance,
    private val changed: (KeyboardAppearance, Boolean) -> Unit,
    chooseImage: () -> Unit,
    removeImage: () -> Unit,
    reset: () -> Unit,
) : LinearLayout(context) {
    constructor(context: Context) : this(context, KeyboardAppearance(), { _, _ -> }, {}, {}, {})
    private var value = initial

    init {
        orientation = VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
        group(R.string.appearance_palette, KeyboardTheme.entries.map { it.label }, initial.theme.ordinal) {
            update(value.copy(theme = KeyboardTheme.entries[it]))
        }
        group(R.string.appearance_material, KeyboardMaterial.entries.map { it.label }, initial.material.ordinal) {
            update(value.copy(material = KeyboardMaterial.entries[it]))
        }
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
        group(R.string.appearance_background, KeyboardBackground.entries.map { it.label }, initial.background.ordinal) {
            val mode = KeyboardBackground.entries[it]
            update(value.copy(background = mode))
            if (mode == KeyboardBackground.IMAGE && value.imageRevision.isEmpty()) chooseImage()
        }
        val colors = listOf(null, 0xffbed5ef.toInt(), 0xff343c49.toInt(), 0xffe8d8bc.toInt(), 0xffebcfdb.toInt())
        group(R.string.appearance_color, listOf(R.string.appearance_color_auto, R.string.appearance_color_blue,
            R.string.appearance_color_gray, R.string.appearance_color_sand, R.string.appearance_color_rose),
            colors.indexOf(initial.backgroundColor).coerceAtLeast(0)) { update(value.copy(backgroundColor = colors[it])) }
        button(R.string.appearance_choose_image, chooseImage)
        button(R.string.appearance_remove_image, removeImage)
        description(R.string.appearance_image_hint)
        slider(R.string.appearance_opacity, initial.backgroundOpacity, 100) { number, commit -> update(value.copy(backgroundOpacity = number), commit) }
        description(R.string.appearance_opacity_hint)
        slider(R.string.appearance_dim, initial.backgroundDim, 80) { number, commit -> update(value.copy(backgroundDim = number), commit) }
        slider(R.string.appearance_blur, initial.backgroundBlur, 20) { number, commit -> update(value.copy(backgroundBlur = number), commit) }
        description(R.string.appearance_glass_hint)
        button(R.string.appearance_reset, reset)
    }

    private fun update(updated: KeyboardAppearance, commit: Boolean = true) {
        value = updated.sanitized()
        changed(value, commit)
    }

    private fun group(title: Int, labels: List<Int>, selected: Int, onSelected: (Int) -> Unit) {
        heading(title)
        addView(RadioGroup(context).apply {
            orientation = VERTICAL
            labels.forEachIndexed { index, label ->
                addView(MaterialRadioButton(context).apply {
                    id = View.generateViewId()
                    setText(label)
                    isChecked = index == selected
                    minHeight = dp(48)
                    setOnClickListener { onSelected(index) }
                }, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        })
    }

    private fun slider(label: Int, initial: Int, limit: Int, onValue: (Int, Boolean) -> Unit) {
        var tracking = false
        val text = TextView(context).apply { text = context.getString(label, initial); setPadding(0, dp(12), 0, 0) }
        addView(text)
        addView(SeekBar(context).apply {
            max = limit
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
        setPadding(0, dp(20), 0, dp(4))
    })

    private fun description(label: Int) = addView(TextView(context).apply { setText(label); setPadding(0, dp(4), 0, dp(8)) })
    private fun button(label: Int, clicked: () -> Unit) = addView(MaterialButton(context).apply {
        setText(label); minHeight = dp(48); setOnClickListener { clicked() }
    }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
