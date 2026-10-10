package dev.zeroinput.ime

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.KeyboardThemeContext
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.KeyboardTheme
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class CompactLandscapeTest {
    @Test fun narrowLandscapeSearchLeavesEditorSpaceAndEveryKeyInsideTheWindow() = onMain {
        for (width in listOf(540, 592, 800)) for (height in listOf(280, 320, 360)) {
            val source = InstrumentationRegistry.getInstrumentation().targetContext
            val config = Configuration(source.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = width
                screenHeightDp = height
            }
            val context = KeyboardThemeContext.create(source.createConfigurationContext(config), KeyboardTheme.CLASSIC)
            val panel = ZeroInputView(context).apply { setKeyboardHeight(KeyboardHeight.COMFORTABLE) }
            descendants(panel).first { it.contentDescription == context.getString(UiR.string.expression_smileys) }.performClick()
            descendants(panel).first { it.contentDescription == context.getString(UiR.string.expression_search) }.performClick()
            panel.measure(View.MeasureSpec.makeMeasureSpec(dp(panel, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(panel, height), View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            assertTrue("Landscape $width x $height must reserve editor space: ${panel.height}/${dp(panel, height - 48)}", panel.height <= dp(panel, height - 48))
            val keyboard = descendants(panel).filterIsInstance<KeyboardPanel>().single()
            for (key in descendants(keyboard).filter { it.isShown }) {
                val bounds = Rect(0, 0, key.width, key.height)
                panel.offsetDescendantRectToMyCoords(key, bounds)
                assertTrue("Every key must remain inside the panel", bounds.bottom <= panel.height && bounds.top >= 0)
                assertTrue(key.width > 0 && key.height > 0)
            }
            panel.release()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
