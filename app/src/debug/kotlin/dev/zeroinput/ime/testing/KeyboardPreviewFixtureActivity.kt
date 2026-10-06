package dev.zeroinput.ime.testing

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import dev.zeroinput.ime.settings.KeyboardThemeContext
import dev.zeroinput.ime.ui.KeyboardTheme
import dev.zeroinput.ime.ui.ZeroInputView

/** Attached public keyboard for rendering checks; never connects to an editor or personal store. */
class KeyboardPreviewFixtureActivity : Activity() {
    lateinit var keyboard: ZeroInputView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        val config = Configuration(resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                if (intent.getBooleanExtra("night", false)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val preset = KeyboardTheme.entries.firstOrNull { it.name == intent.getStringExtra("theme") } ?: KeyboardTheme.CLASSIC
        val context = KeyboardThemeContext.create(createConfigurationContext(config), preset)
        keyboard = ZeroInputView(context)
        keyboard.applyAppearance(dev.zeroinput.ime.ui.KeyboardAppearance(theme = preset))
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceVariant, android.graphics.Color.GRAY))
            addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    override fun onSaveInstanceState(outState: Bundle) { /* Public fixture has no saved state. */ }
}
