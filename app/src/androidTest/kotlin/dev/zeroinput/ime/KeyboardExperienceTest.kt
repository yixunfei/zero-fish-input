package dev.zeroinput.ime

import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.EditorInputOptions
import dev.zeroinput.ime.core.EditorLayout
import dev.zeroinput.ime.core.EnterAction
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.settings.KeyboardThemeContext
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class KeyboardExperienceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun idleComposingAndToolsShareTheSameCompactHeaderWithoutMovingKeys() = onMain {
        val panel = panel()
        measure(panel)
        val keyboard = views(panel).filterIsInstance<KeyboardPanel>().single()
        val point = top(panel, keyboard)
        val height = panel.height
        assertEquals(dp(panel, 72), point)
        assertEquals(dp(panel, 72) + 4 * dp(panel, 52), height)
        assertFalse(views(panel).filterIsInstance<CandidateStripView>().single().isShown)
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("nihao", "ni hao", listOf(Candidate("fixture", "你好")))))
        measure(panel)
        assertEquals(point, top(panel, keyboard))
        assertEquals(height, panel.height)
        click(panel, UiR.string.keyboard_tools)
        measure(panel)
        assertEquals(point, top(panel, keyboard))
        click(panel, UiR.string.keyboard_return)
        panel.renderSession(InputSessionState())
        measure(panel)
        assertEquals(height, panel.height)
    }

    @Test fun heightAndThemeVariantsKeepTextAndTouchTargetsInsideTheWindow() = onMain {
        for (night in listOf(false, true)) for (theme in KeyboardTheme.entries) for (height in KeyboardHeight.entries) {
            for (width in listOf(320, 800)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    orientation = if (width == 800) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = width
                    fontScale = 1.3f
                    uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = KeyboardThemeContext.create(instrumentation.targetContext.createConfigurationContext(config), theme)
                val panel = ZeroInputView(context).apply { setKeyboardHeight(height) }
                measure(panel, width)
                val keyboard = views(panel).filterIsInstance<KeyboardPanel>().single()
                assertTrue(panel.height <= dp(panel, if (width == 800) 300 else 340))
                for (i in 0 until keyboard.childCount) assertTrue(keyboard.getChildAt(i).height >= dp(panel, 48))
                val foreground = color(context, com.google.android.material.R.attr.colorOnSurface)
                for (background in listOf(com.google.android.material.R.attr.colorSurface, com.google.android.material.R.attr.colorPrimaryContainer)) {
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground, color(context, background)) >= 4.5)
                }
                views(keyboard).filterIsInstance<TextView>().forEach { key ->
                    assertTrue(key.height >= key.layout.height)
                    assertEquals(0, key.layout.getEllipsisCount(0))
                }
            }
        }
    }

    @Test fun numericPhoneAndDecimalKeysAreLiteralAndRespectEditorFlags() = onMain {
        val panel = panel()
        val actions = mutableListOf<KeyboardAction>()
        panel.onKeyboardAction = { actions += it }
        panel.startEditor(EditorInputOptions(EditorLayout.NUMBER, signed = true, decimal = true))
        measure(panel)
        for (value in listOf("-", "1", ".", "2")) key(panel, value).performClick()
        assertEquals(listOf("-", "1", ".", "2").map(KeyboardAction::LiteralText), actions)
        panel.startEditor(EditorInputOptions(EditorLayout.NUMBER))
        measure(panel)
        assertFalse(views(panel).filterIsInstance<TextView>().any { it.text.toString() in setOf(".", "-") && it.isEnabled })
        panel.startEditor(EditorInputOptions(EditorLayout.PHONE))
        measure(panel)
        for (value in listOf("+", "*", "#")) assertTrue(key(panel, value).isEnabled)
    }

    @Test fun shiftReusesKeysRejectsAnOldGestureAndResetsAfterOneCharacterOrNewSession() = onMain {
        val panel = panel()
        val actions = mutableListOf<KeyboardAction>()
        panel.onKeyboardAction = { actions += it }
        measure(panel)
        val before = key(panel, "q")
        touch(before, MotionEvent.ACTION_DOWN)
        click(panel, UiR.string.key_shift)
        assertSame(before, key(panel, "Q"))
        touch(before, MotionEvent.ACTION_UP)
        assertTrue(actions.isEmpty())
        touch(before, MotionEvent.ACTION_DOWN)
        touch(before, MotionEvent.ACTION_UP)
        assertEquals(listOf(KeyboardAction.Text("Q")), actions)
        assertSame(before, key(panel, "q"))
        key(panel, panel.context.getString(UiR.string.key_shift)).performLongClick()
        assertNotNull(key(panel, "Q"))
        panel.startEditor(EditorInputOptions())
        assertNotNull(key(panel, "q"))
    }

    @Test fun everySymbolPageHasBackspaceAndEnterLabelsFollowComposition() = onMain {
        val panel = panel()
        panel.startEditor(EditorInputOptions(enterAction = EnterAction.SEARCH))
        measure(panel)
        assertNotNull(key(panel, panel.context.getString(UiR.string.key_search)))
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("fixture", "你")))))
        assertNotNull(key(panel, panel.context.getString(UiR.string.key_enter)))
        click(panel, UiR.string.key_symbols)
        measure(panel)
        click(panel, UiR.string.key_more_symbols)
        measure(panel)
        var deleted = false
        panel.onKeyboardAction = { if (it == KeyboardAction.Backspace) deleted = true }
        click(panel, UiR.string.key_backspace)
        assertTrue(deleted)
    }

    @Test fun retiredThemeViewCannotSendOldKeyCandidateOrToolbarActions() = onMain {
        val panel = panel()
        var actions = 0
        panel.onKeyboardAction = { actions++ }
        panel.onCandidateSelected = { _, _ -> actions++ }
        panel.onSettingsRequested = { actions++ }
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("fixture", "你")))))
        measure(panel)
        val letter = key(panel, "q")
        val candidate = key(panel, "你")
        val settings = views(panel).first { it.contentDescription?.toString() == panel.context.getString(UiR.string.keyboard_settings) }
        touch(letter, MotionEvent.ACTION_DOWN)
        touch(candidate, MotionEvent.ACTION_DOWN)
        panel.release()
        touch(letter, MotionEvent.ACTION_UP)
        touch(candidate, MotionEvent.ACTION_UP)
        settings.performClick()
        assertEquals(0, actions)
        assertEquals("", (candidate as TextView).text.toString())
    }

    private fun panel() = ZeroInputView(KeyboardThemeContext.create(instrumentation.targetContext, KeyboardTheme.CLASSIC))
    private fun key(root: View, label: String): View = available(root).first {
        (it.contentDescription?.toString() == label || it is TextView && it.text.toString() == label) }
    private fun available(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { available(view.getChildAt(it)) } else emptyList()
    private fun click(root: View, label: Int) { key(root, root.context.getString(label)).performClick() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun top(root: ViewGroup, view: View): Int = android.graphics.Rect(0, 0, view.width, view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }.top
    private fun measure(view: View, width: Int = 320) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view, 1000), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun color(context: android.content.Context, attr: Int) = com.google.android.material.color.MaterialColors.getColor(context, attr, 0)
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
