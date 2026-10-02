package dev.zeroinput.ime

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.ui.CandidateStripView
import dev.zeroinput.ime.ui.EmojiPanelView
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.SecureClipboardPanelView
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.InputEngineStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputPanelTest {
    @Test
    fun associationsKeepKeyboardSizeAndCandidateIdentityAcrossThemesAndOrientations() = onMain {
        for (night in listOf(false, true)) for (width in listOf(320, 411, 800)) {
            val landscape = width == 800
            val panel = panel(night, landscape)
            measure(panel, width)
            val height = panel.measuredHeight
            val candidates = listOf(Candidate("association:1:0", "世界",
                kind = dev.zeroinput.engine.api.CandidateKind.NEXT_WORD))
            panel.renderSession(InputSessionState(snapshot = EngineSnapshot(candidates = candidates)))
            measure(panel, width)
            assertTrue("Associations must preserve keyboard height", panel.measuredHeight == height)
            assertTrue(visible(panel).any { it is KeyboardPanel })
            assertTrue(visible(panel).filterIsInstance<TextView>().any {
                it.text == panel.context.getString(dev.zeroinput.ime.ui.R.string.word_associations)
            })
            assertFalse(visible(panel).any {
                it.contentDescription == panel.context.getString(dev.zeroinput.ime.ui.R.string.expand_candidates)
            })
            var selectedId = ""
            panel.onCandidateSelected = { _, id -> selectedId = id }
            button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.candidate_description, "世界")).performClick()
            assertTrue(selectedId == candidates.single().id)
            assertLabelsFit(panel)
            panel.release()
        }
    }

    @Test
    fun compositionAndLoadingStatusDoNotTakeWidthFromCandidates() = onMain {
        val panel = panel(false)
        panel.renderEngineStatus(InputEngineStatus.PREPARING)
        panel.renderSession(candidateState())
        measure(panel, 320)
        val candidateViews = visible(panel).filterIsInstance<TextView>().filter {
            it.contentDescription?.toString()?.startsWith(panel.context.getString(dev.zeroinput.ime.ui.R.string.candidate_description, "")) == true
        }
        val strip = visible(panel).filterIsInstance<CandidateStripView>().single()
        val fullyVisible = candidateViews.count {
            val rect = Rect(0, 0, it.width, it.height)
            strip.offsetDescendantRectToMyCoords(it, rect)
            rect.left >= 0 && rect.right <= strip.width - dp(panel, 48)
        }
        assertTrue("A 320dp keyboard must show at least four short candidates", fullyVisible >= 4)
    }

    @Test
    fun expandedCandidatesKeepHeightAndRoutePagingAndSelection() = onMain {
        val panel = panel(false)
        panel.renderSession(candidateState())
        var pageRequests = 0
        var selection = -1
        panel.onCandidatePageChanged = { pageRequests++ }
        panel.onCandidateSelected = { index, _ -> selection = index }
        measure(panel, 320)
        val height = panel.height
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.expand_candidates)).performClick()
        measure(panel, 320)
        assertTrue("Expanding candidates must preserve keyboard height", panel.height == height)
        assertFalse(visible(panel).any { it is KeyboardPanel })
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.next_candidates)).performClick()
        assertTrue(pageRequests == 1)
        visible(panel).last { it.contentDescription == panel.context.getString(dev.zeroinput.ime.ui.R.string.candidate_description, "你好") }.performClick()
        assertTrue(selection >= 0)
        var interactions = 0
        panel.onUserInteraction = { interactions++ }
        panel.renderSession(InputSessionState(canReconvert = true))
        assertTrue("Automatic return to keyboard must preserve the recent word", interactions == 0)
        var reopened = false
        panel.onReconvertRequested = { reopened = true }
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.reconvert_last_word)).performClick()
        assertTrue(reopened)
        assertTrue(visible(panel).any { it is KeyboardPanel })
        assertFalse(visible(panel).any { it.contentDescription == panel.context.getString(dev.zeroinput.ime.ui.R.string.candidate_description, "你好") })
    }

    @Test
    fun candidateChangedDuringTouchIsNotSelected() = onMain {
        val panel = panel(false)
        panel.renderSession(candidateState())
        measure(panel, 320)
        var selected = false
        panel.onCandidateSelected = { _, _ -> selected = true }
        val target = button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.candidate_description, "你好"))
        touch(target, MotionEvent.ACTION_DOWN)
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("hao", "hao", listOf(Candidate("changed", "好")))))
        target.performClick()
        assertFalse("A late click must not select a replacement candidate", selected)
        touch(target, MotionEvent.ACTION_CANCEL)
    }

    @Test
    fun holdingBackspaceRepeatsUntilTheGestureIsCancelled() {
        lateinit var panel: ZeroInputView
        val repeated = CountDownLatch(2)
        var deletes = 0
        onMain {
            panel = panel(false)
            measure(panel, 320)
            panel.onKeyboardAction = { if (it == KeyboardAction.Backspace) { deletes++; repeated.countDown() } }
            touch(button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.key_backspace)), MotionEvent.ACTION_DOWN)
        }
        assertTrue("Holding backspace must repeat", repeated.await(2, TimeUnit.SECONDS))
        var countAtCancellation = 0
        onMain { panel.cancelPendingGestures(); countAtCancellation = deletes }
        Thread.sleep(180)
        onMain { assertTrue("Cancellation must remove pending deletes", deletes == countAtCancellation) }
    }

    @Test
    fun longBackspaceClearsCompositionWithoutContinuingIntoEditorText() {
        lateinit var panel: ZeroInputView
        val cleared = CountDownLatch(1)
        var deletes = 0
        onMain {
            panel = panel(false)
            measure(panel, 320)
            panel.onClearCompositionRequested = { cleared.countDown(); true }
            panel.onKeyboardAction = { if (it == KeyboardAction.Backspace) deletes++ }
            touch(button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.key_backspace)), MotionEvent.ACTION_DOWN)
        }
        assertTrue("Long press must request composition clearing", cleared.await(2, TimeUnit.SECONDS))
        Thread.sleep(180)
        onMain {
            panel.cancelPendingGestures()
            assertTrue("The same hold must not delete committed text", deletes == 0)
        }
    }

    private fun candidateState() = InputSessionState(snapshot = EngineSnapshot(
        "nihaozhongguoshijie", "ni hao zhong guo shi jie",
        List(8) { Candidate("fixture:$it", if (it % 2 == 0) "你好" else "你") },
        hasNextPage = true,
    ))

    private fun touch(view: View, action: Int) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, 5f, 5f, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    @Test
    fun landscapeEmojiSearchKeepsEveryKeyboardRowAndPanelControlInsideWindow() = onMain {
        val panel = panel(false, landscape = true)
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.expression_smileys)).performClick()
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.expression_search)).performClick()
        measure(panel, 800, 360)
        val keyboard = visible(panel).filterIsInstance<KeyboardPanel>().single()
        val emoji = visible(panel).filterIsInstance<EmojiPanelView>().single()
        for (view in visible(keyboard) + listOf(emoji, button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.keyboard_return)))) {
            val bounds = Rect(0, 0, view.width, view.height)
            panel.offsetDescendantRectToMyCoords(view, bounds)
            assertTrue("Keyboard rows and return control must fit the input window", bounds.top >= 0 && bounds.bottom <= panel.height)
        }
        assertLabelsFit(panel)
    }

    @Test
    fun systemBarsAndDisplayCutoutStayOutsidePanelControls() = onMain {
        val panel = panel(false)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setInsets(WindowInsetsCompat.Type.captionBar(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(20, 0, 12, 0))
            .build()
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(panel, insets) }
        measure(panel, 320)
        assertTrue("Repeated inset delivery must reserve the system control area once", panel.paddingBottom == 48)
        assertTrue("Side cutouts must stay outside the keys", panel.paddingLeft == 20 && panel.paddingRight == 12)
        val lastRow = visible(panel).filterIsInstance<KeyboardPanel>().single()
        assertTrue("Keyboard must end above the system controls", lastRow.bottom <= panel.height - 48)
        ViewCompat.dispatchApplyWindowInsets(panel, WindowInsetsCompat.Builder().build())
        assertTrue("Changed geometry must remove obsolete padding", panel.paddingBottom == 0 && panel.paddingLeft == 0)
    }

    @Test
    fun operationLabelsFitInLightAndDarkThemes() = onMain {
        for (night in listOf(false, true)) {
            for (width in listOf(320, 411, 800)) {
                val panel = panel(night)
                measure(panel, width)
                assertLabelsFit(panel)
                panel.renderSession(InputSessionState(snapshot = EngineSnapshot(
                    rawInput = "ni",
                    composition = "ni",
                    candidates = listOf(Candidate("fixture", "你")),
                    hasNextPage = true,
                    hasPreviousPage = true,
                )))
                measure(panel, width)
                assertLabelsFit(panel)
            }
        }
    }

    @Test
    fun clipboardAndEmojiKeepReturnAndSearchControlsUsable() = onMain {
        val panel = panel(false)
        var interactions = 0
        panel.onUserInteraction = { interactions++ }
        measure(panel, 320)
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_open)).performClick()
        panel.renderSecureClipboard(false, emptyList())
        measure(panel, 320)
        assertLabelsFit(panel)
        assertTrue(visible(panel).any { it is SecureClipboardPanelView })
        assertFalse(visible(panel).any { it is KeyboardPanel || it is CandidateStripView })
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.keyboard_return)).performClick()
        assertTrue(visible(panel).any { it is KeyboardPanel })
        assertFalse(visible(panel).any { it is SecureClipboardPanelView })
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.expression_smileys)).performClick()
        measure(panel, 320)
        assertLabelsFit(panel)
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.expression_search)).performClick()
        measure(panel, 320)
        val searchHeight = panel.measuredHeight
        // Switching panels preserves search; Back intentionally closes it one level at a time.
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_open)).performClick()
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.expression_smileys)).performClick()
        measure(panel, 320)
        assertTrue("Returning to active emoji search must preserve its height", panel.measuredHeight == searchHeight)
        assertTrue("Panel changes must invalidate pending authenticated actions", interactions >= 5)
    }

    @Test
    fun secureClipboardManagementUsesACompactActionTarget() = onMain {
        val panel = panel(false)
        measure(panel, 320)
        button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_open)).performClick()
        panel.renderSecureClipboard(false, emptyList())
        measure(panel, 320)
        val manage = button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_manage))
        assertTrue("Clipboard management must remain a compact toolbar action", manage.width <= dp(panel, 56))
        assertTrue("Clipboard management must remain inside the panel", manage.right <= panel.width)
        panel.release()
    }

    @Test fun handwritingPanelFitsSmallLayoutsAndSpaceRequiresAcceptedCandidate() = onMain {
        for (landscape in listOf(false, true)) {
            val panel = panel(false, landscape)
            val width = if (landscape) 800 else 320
            val strokeUpdates = ArrayList<Int>()
            var spaceActions = 0
            var accept = false
            panel.onHandwritingStrokesChanged = { strokes ->
                strokeUpdates.add(strokes.size)
                strokes.forEach { it.fill(0f) }
            }
            panel.onHandwritingCandidateSelected = { accept }
            panel.onKeyboardAction = { if (it == KeyboardAction.Space) spaceActions++ }
            measure(panel, width, if (landscape) 320 else 600)
            button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.handwriting_open)).performClick()
            measure(panel, width, if (landscape) 320 else 600)
            val canvas = button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.handwriting_canvas))
            assertTrue(canvas.width > 0 && canvas.height > 0)
            val updatesBeforeStroke = strokeUpdates.size
            val now = SystemClock.uptimeMillis()
            for ((action, x, y) in listOf(
                Triple(MotionEvent.ACTION_DOWN, canvas.width * 0.2f, canvas.height * 0.5f),
                Triple(MotionEvent.ACTION_UP, canvas.width * 0.8f, canvas.height * 0.5f),
            )) {
                val event = MotionEvent.obtain(now, now, action, x, y, 0)
                canvas.dispatchTouchEvent(event)
                event.recycle()
                if (action == MotionEvent.ACTION_DOWN) {
                    assertEquals("Touch-down must immediately revoke the previous recognition", updatesBeforeStroke + 1, strokeUpdates.size)
                    assertEquals(0, strokeUpdates.last())
                }
            }
            assertEquals("A stroke must invalidate first, then publish one completed trace", listOf(0, 1), strokeUpdates.drop(updatesBeforeStroke))
            button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.handwriting_space)).performClick()
            assertTrue(spaceActions == 0)
            panel.renderHandwritingCandidates(listOf("中"))
            button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.handwriting_space)).performClick()
            assertTrue(spaceActions == 0)
            accept = true
            button(panel, panel.context.getString(dev.zeroinput.ime.ui.R.string.handwriting_space)).performClick()
            assertTrue(spaceActions == 1)
            measure(panel, width, if (landscape) 320 else 600)
            assertLabelsFit(panel)
            panel.release()
        }
    }

    private fun panel(night: Boolean, landscape: Boolean = false): ZeroInputView {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = if (landscape) 800 else 411
            screenHeightDp = if (landscape) 411 else 914
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_ZeroInput_InputMethod)
        return ZeroInputView(themed)
    }

    private fun measure(view: View, widthDp: Int, heightDp: Int = 1000) {
        val density = view.resources.displayMetrics.density
        view.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertLabelsFit(root: View) {
        visible(root).filterIsInstance<TextView>().filter { it.isClickable && it.text.isNotEmpty() }.forEach { button ->
            val available = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
            assertTrue("Button '${button.text}': width=${button.width}, padding=${button.compoundPaddingLeft + button.compoundPaddingRight}, text=${button.paint.measureText(button.text.toString())}",
                available >= button.paint.measureText(button.text.toString()))
            val layout = button.layout
            assertTrue("Button label must have a text layout", layout != null && layout.lineCount > 0)
            assertTrue("Button label must not be ellipsized", layout.getEllipsisCount(0) == 0)
        }
    }

    private fun button(root: View, description: String): View =
        visible(root).first { it.contentDescription?.toString() == description }

    private fun visible(root: View): List<View> {
        if (root.visibility != View.VISIBLE) return emptyList()
        val children = if (root is ViewGroup) (0 until root.childCount).flatMap { visible(root.getChildAt(it)) } else emptyList()
        return listOf(root) + children
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
