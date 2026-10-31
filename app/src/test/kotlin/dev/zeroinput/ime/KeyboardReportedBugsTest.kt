package dev.zeroinput.ime

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.zeroinput.ime.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KeyboardReportedBugsTest {
    @Test fun visibleGroupSelectorJumpsToMathematics() = withActivity { activity ->
        val panel = KeyboardPanel(activity)
        activity.setContentView(panel)
        key(panel, UiR.string.key_symbols).performClick()
        key(panel, UiR.string.symbol_groups).performClick()
        val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(popup)
        assertEquals(12, popup.menu.size())
        popup.menu.performIdentifierAction(2, 0)
        assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.text.toString() == "∫" })
    }

    @Test fun englishPunctuationKeepsItsEngineRoute() = withActivity { activity ->
        val panel = KeyboardPanel(activity)
        panel.setLanguage(dev.zeroinput.engine.api.InputLanguage.ENGLISH)
        var action: KeyboardAction? = null
        panel.onAction = { action = it }
        key(panel, UiR.string.key_comma).performClick()
        assertEquals(KeyboardAction.Text(","), action)
    }

    @Test fun chineseShiftCommitsLiteralHalfWidthPunctuation() = withActivity { activity ->
        val panel = KeyboardPanel(activity)
        var action: KeyboardAction? = null
        panel.onAction = { action = it }
        key(panel, UiR.string.key_shift).performClick()
        assertEquals(",", (key(panel, UiR.string.key_comma) as TextView).text.toString())
        key(panel, UiR.string.key_comma).performClick()
        assertEquals(KeyboardAction.LiteralText(","), action)
        assertEquals("，", (key(panel, UiR.string.key_comma) as TextView).text.toString())
    }

    @Test fun symbolsCanBrowseBeyondTwoPagesAndSwipeWithoutCommitting() = withActivity { activity ->
        val panel = KeyboardPanel(activity)
        activity.setContentView(panel)
        panel.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 600, 300)
        var commits = 0
        panel.onAction = { commits++ }
        key(panel, UiR.string.key_symbols).performClick()
        fun labels() = descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }
        val first = labels()
        key(panel, UiR.string.key_more_symbols).performClick()
        val second = labels()
        swipe(panel, -1)
        assertNotEquals(first, labels())
        assertNotEquals(second, labels())
        swipe(panel, 1)
        assertEquals(second, labels())
        assertEquals(0, commits)
        val pages = mutableSetOf<List<String>>()
        repeat(60) {
            pages += labels()
            key(panel, UiR.string.key_more_symbols).performClick()
        }
        assertTrue(pages.size >= 30)
        assertTrue(pages.flatten().containsAll(listOf("【", "∫", "→", "℃", "ǜ")))
    }

    @Test fun privateCopyIsVisibleWithoutOpeningMoreTools() = withActivity { activity ->
        val panel = ZeroInputView(activity)
        activity.setContentView(panel)
        panel.renderCopySelectionAvailable(true)
        val copy = descendants(panel).first { it.contentDescription == activity.getString(UiR.string.secure_clipboard_copy_selection) && it.isShown }
        var requests = 0
        panel.onCopySelectionRequested = { requests++ }
        copy.performClick()
        assertEquals(1, requests)
        panel.renderCopySelectionAvailable(false)
        assertFalse(copy.isShown)
        panel.release()
    }

    private fun key(view: View, id: Int) = descendants(view).first { it.contentDescription == view.context.getString(id) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun swipe(view: View, direction: Int) {
        val now = SystemClock.uptimeMillis()
        val start = if (direction < 0) 500f else 100f
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(now, now + index * 30L, action, start + direction * index * 180f, 60f, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try { block(owner.get().apply { setTheme(R.style.Theme_ZeroInput) }) }
        finally { owner.pause().stop().destroy() }
    }
}
