package dev.zeroinput.ime

import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.ui.CandidateStripView
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.KeyboardLayoutHost
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputExperienceRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput_InputMethod)

    @Test fun smallFingerDriftStillTypesButCancelledGestureDoesNot() = onMain {
        val panel = KeyboardPanel(context)
        measure(panel)
        val key = descendants(panel).filterIsInstance<TextView>().first { it.text == "q" }
        val actions = mutableListOf<KeyboardAction>()
        panel.onAction = { actions += it }
        touch(key, MotionEvent.ACTION_DOWN, key.width - 1f)
        touch(key, MotionEvent.ACTION_MOVE, key.width + 1f)
        touch(key, MotionEvent.ACTION_UP, key.width + 1f)
        assertEquals(listOf(KeyboardAction.Text("q")), actions)
        touch(key, MotionEvent.ACTION_DOWN)
        touch(key, MotionEvent.ACTION_CANCEL)
        touch(key, MotionEvent.ACTION_UP)
        assertEquals(1, actions.size)
    }

    @Test fun overlappingShiftedKeysKeepTheirPressedCharacters() = onMain {
        val panel = KeyboardPanel(context)
        measure(panel)
        descendants(panel).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.key_shift) }.performClick()
        val first = descendants(panel).filterIsInstance<TextView>().first { it.text == "Q" }
        val second = descendants(panel).filterIsInstance<TextView>().first { it.text == "W" }
        val actions = mutableListOf<KeyboardAction>()
        panel.onAction = { actions += it }
        touch(first, MotionEvent.ACTION_DOWN)
        touch(second, MotionEvent.ACTION_DOWN)
        touch(first, MotionEvent.ACTION_UP)
        touch(second, MotionEvent.ACTION_UP)
        assertEquals(listOf(KeyboardAction.Text("Q"), KeyboardAction.Text("W")), actions)
    }

    @Test fun refreshingSameCompositionKeepsCandidateBrowsingPosition() = onMain {
        val strip = CandidateStripView(context)
        val candidates = List(24) { Candidate("fixture:$it", "候选$it") }
        strip.render(EngineSnapshot("ni", "ni", candidates))
        measure(strip)
        val scroll = descendants(strip).filterIsInstance<HorizontalScrollView>().single()
        scroll.scrollTo(240, 0)
        val previous = scroll.scrollX
        assertTrue(previous > 0)
        strip.render(EngineSnapshot("ni", "ni", candidates + Candidate("fixture:new", "新增")))
        measure(strip)
        assertEquals(previous, scroll.scrollX)
        strip.render(EngineSnapshot("nin", "nin", candidates))
        measure(strip)
        assertEquals(0, scroll.scrollX)
    }

    @Test fun longFirstCandidateLeavesRoomForOtherChoices() = onMain {
        val strip = CandidateStripView(context)
        strip.render(EngineSnapshot("fixture", "fixture", listOf(
            Candidate("fixture:long", "这是用于验证长候选展示范围的公开测试句子"), Candidate("fixture:short", "你好"),
        )))
        measure(strip)
        val scroll = descendants(strip).filterIsInstance<HorizontalScrollView>().single()
        val first = descendants(scroll).filterIsInstance<TextView>().first()
        assertTrue(first.width <= scroll.width * 0.66f)
    }

    @Test fun numberPadStaysOpenAndReturnsToLettersWithoutChangingLanguage() = onMain {
        val panel = KeyboardPanel(context)
        panel.setLanguage(dev.zeroinput.engine.api.InputLanguage.ENGLISH)
        measure(panel)
        val originalHeight = panel.height
        val actions = mutableListOf<KeyboardAction>()
        var pages = 0
        panel.onAction = { actions += it }
        panel.onPageChanged = { pages++ }
        panel.showNumbers()
        measure(panel)
        assertEquals(originalHeight, panel.height)
        repeat(2) { descendants(panel).filterIsInstance<TextView>().first { it.text == "1" }.performClick() }
        assertEquals(listOf(KeyboardAction.LiteralText("1"), KeyboardAction.LiteralText("1")), actions)
        assertTrue(panel.canNavigateBack)
        assertEquals(1, pages)
        assertTrue(panel.navigateBack())
        measure(panel)
        assertFalse(panel.canNavigateBack)
        assertNotNull(descendants(panel).filterIsInstance<TextView>().firstOrNull { it.text == "En" })
        assertEquals(2, pages)
    }

    @Test fun changingPageCancelsHeldLetterAndNumbersAreReachableFromSymbols() = onMain {
        val panel = KeyboardPanel(context)
        measure(panel)
        val actions = mutableListOf<KeyboardAction>()
        panel.onAction = { actions += it }
        val letter = descendants(panel).filterIsInstance<TextView>().first { it.text == "q" }
        touch(letter, MotionEvent.ACTION_DOWN)
        panel.showNumbers()
        touch(letter, MotionEvent.ACTION_UP)
        assertTrue(actions.isEmpty())
        measure(panel)
        descendants(panel).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.key_symbols) }.performClick()
        measure(panel)
        descendants(panel).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.key_numbers) }.performClick()
        assertTrue(panel.navigateBack())
        assertFalse(panel.canNavigateBack)
    }

    @Test fun idleHeaderKeepsSecondaryToolsHiddenAndReturnFixedDuringScrolling() = onMain {
        val panel = ZeroInputView(context)
        measure(panel)
        val label = { id: Int -> descendants(panel).first { it.contentDescription == context.getString(id) && visibleWithin(it, panel) } }
        assertFalse(descendants(panel).any {
            it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.keyboard_settings) && visibleWithin(it, panel)
        })
        label(dev.zeroinput.ime.ui.R.string.keyboard_tools).performClick()
        measure(panel)
        val back = label(dev.zeroinput.ime.ui.R.string.keyboard_return)
        val before = IntArray(2).also(back::getLocationInWindow)
        descendants(panel).filterIsInstance<HorizontalScrollView>().filter { visibleWithin(it, panel) }.forEach { it.scrollTo(1000, 0) }
        assertArrayEquals(before, IntArray(2).also(back::getLocationInWindow))
        back.performClick()
        assertFalse(panel.canNavigateBack)
        panel.release()
    }

    @Test fun hostedHandwritingKeepsTheHeaderStableAcrossParentMeasurePasses() = onMain {
        val panel = ZeroInputView(context)
        val host = KeyboardLayoutHost(context, panel)
        measure(host)
        descendants(panel).first {
            it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.keyboard_tools)
        }.performClick()
        descendants(panel).first {
            it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.handwriting_open)
        }.performClick()
        measure(host)
        val stableHeight = panel.height
        repeat(4) {
            measure(host)
            assertEquals(stableHeight, panel.height)
            assertFalse("Layout must settle after opening handwriting", host.isLayoutRequested)
        }
        host.release()
        panel.release()
    }

    private fun visibleWithin(view: View, root: View): Boolean = view.visibility == View.VISIBLE &&
        (view === root || (view.parent as? View)?.let { visibleWithin(it, root) } == true)

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun measure(view: View) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun touch(view: View, action: Int, x: Float = view.width / 2f) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, x, view.height / 2f, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
