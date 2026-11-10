package dev.zeroinput.ime

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.ime.core.EditorInputOptions
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.KeyboardPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.zeroinput.ime.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KeyboardPageContinuityTest {
    @Test fun restartedEditorKeepsNumericPageThroughDigitsDeletionSpaceAndEnter() = withKeyboard { keyboard ->
        val options = EditorInputOptions()
        val actions = mutableListOf<KeyboardAction>()
        keyboard.onAction = { actions += it; keyboard.startEditor(options, restarting = true) }
        keyboard.showNumbers()
        for (digit in "123.45") label(keyboard, digit.toString()).performClick()
        for (id in listOf(UiR.string.key_backspace, UiR.string.key_space, UiR.string.key_enter)) {
            key(keyboard, id).performClick()
            assertNotNull(label(keyboard, "1"))
        }
        assertEquals(9, actions.size)
        keyboard.startEditor(options)
        assertNotNull(label(keyboard, "q"))
    }

    @Test fun restartedSymbolsKeepPageButChangedEditorOptionsResetIt() = withKeyboard { keyboard ->
        key(keyboard, UiR.string.key_symbols).performClick()
        key(keyboard, UiR.string.key_more_symbols).performClick()
        val symbols = labels(keyboard)
        keyboard.startEditor(EditorInputOptions(), restarting = true)
        assertEquals(symbols, labels(keyboard))
        keyboard.startEditor(EditorInputOptions(layout = dev.zeroinput.ime.core.EditorLayout.PHONE), restarting = true)
        assertNotNull(label(keyboard, "+"))
        assertFalse(keyboard.canNavigateBack)
    }

    @Test fun engineLayoutRefreshPreservesContinuousNumbersAndSymbols() = withKeyboard { keyboard ->
        val actions = mutableListOf<KeyboardAction>()
        keyboard.onAction = { actions += it }
        keyboard.showNumbers()
        for ((index, digit) in "12345".withIndex()) {
            keyboard.setKeyboardLayout(if (index % 2 == 0) ChineseKeyboardLayout.NINE_KEY else ChineseKeyboardLayout.FULL)
            label(keyboard, digit.toString()).performClick()
        }
        assertEquals("12345".map { KeyboardAction.LiteralText(it.toString()) }, actions)
        key(keyboard, UiR.string.key_symbols).performClick()
        key(keyboard, UiR.string.key_more_symbols).performClick()
        val symbols = labels(keyboard)
        keyboard.setKeyboardLayout(ChineseKeyboardLayout.FULL)
        assertEquals(symbols, labels(keyboard))
    }

    private fun labels(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun label(view: View, label: String) = descendants(view).filterIsInstance<TextView>().first { it.text.toString() == label }
    private fun key(view: View, id: Int) = descendants(view).first { it.contentDescription == view.context.getString(id) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun withKeyboard(block: (KeyboardPanel) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = owner.get().apply { setTheme(R.style.Theme_ZeroInput) }
            block(KeyboardPanel(activity).also { activity.setContentView(it); it.startEditor(EditorInputOptions()) })
        } finally { owner.pause().stop().destroy() }
    }
}
