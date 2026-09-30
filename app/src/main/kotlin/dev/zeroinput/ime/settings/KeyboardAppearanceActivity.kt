package dev.zeroinput.ime.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.radiobutton.MaterialRadioButton
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardTheme
import dev.zeroinput.ime.ui.ZeroInputView

class KeyboardAppearanceActivity : AppCompatActivity() {
    private val settings by lazy { (application as ZeroInputApplication).graph.settings }
    private lateinit var preview: FrameLayout
    private var keyboard: ZeroInputView? = null
    private var selected = KeyboardAppearance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = settings.keyboardAppearance
        setContentView(content())
        updatePreview()
    }

    override fun onPause() { keyboard?.cancelPendingGestures(); super.onPause() }
    override fun onDestroy() { keyboard?.release(); super.onDestroy() }

    private fun content() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        addView(MaterialToolbar(this@KeyboardAppearanceActivity).apply {
            setTitle(R.string.keyboard_appearance)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = getString(R.string.navigate_up)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        addView(ScrollView(this@KeyboardAppearanceActivity).apply {
            addView(options())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun options() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(com.google.android.material.switchmaterial.SwitchMaterial(context).apply {
            setText(dev.zeroinput.ime.ui.R.string.glide_enabled)
            isChecked = settings.glideTypingEnabled
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            setOnCheckedChangeListener { _, enabled -> settings.glideTypingEnabled = enabled }
        })
        addView(TextView(context).apply {
            setText(dev.zeroinput.ime.ui.R.string.glide_description)
            setPadding(dp(16), 0, dp(16), dp(8))
        })
        addView(title(R.string.keyboard_style))
        addView(RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            KeyboardTheme.entries.forEach { preset ->
                addView(radio(preset.label, preset == selected.theme).apply {
                    val themed = KeyboardThemeContext.create(context, preset)
                    val color = com.google.android.material.color.MaterialColors.getColor(themed,
                        com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.GRAY)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat(); setSize(dp(48), dp(24)) }, null)
                    setOnClickListener { select(selected.copy(theme = preset)) }
                })
            }
        })
        addView(title(R.string.keyboard_height))
        addView(RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            KeyboardHeight.entries.forEach { height ->
                addView(radio(height.label, height == selected.height).apply {
                    textSize = 14f
                    setPadding(0, 0, 0, 0)
                    layoutParams = RadioGroup.LayoutParams(0, dp(48), 1f)
                    setOnClickListener { select(selected.copy(height = height)) }
                })
            }
        })
        addView(title(R.string.keyboard_preview))
        preview = FrameLayout(context)
        addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun radio(label: Int, checked: Boolean) = MaterialRadioButton(this).apply {
        id = View.generateViewId()
        setText(label)
        isChecked = checked
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(16), 0)
        layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun title(label: Int) = TextView(this).apply {
        setText(label)
        textSize = 14f
        setPadding(dp(16), dp(16), dp(16), dp(8))
    }

    private fun select(appearance: KeyboardAppearance) {
        if (selected == appearance) return
        selected = appearance
        settings.keyboardAppearance = appearance
        updatePreview()
    }

    private fun updatePreview() {
        keyboard?.release()
        preview.removeAllViews()
        keyboard = ZeroInputView(KeyboardThemeContext.create(this, selected.theme)).apply {
            setKeyboardHeight(selected.height)
            // This preview has no editor, engine or personal-data callbacks.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }.also { preview.addView(it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
