package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateViewportResetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun resettingCandidatesDoesNotMoveKeyboardFocus() = withPanel { panel ->
        lateinit var tools: View
        onMain { panel.renderSession(state("n")) }
        instrumentation.waitForIdleSync()
        onMain {
            tools = descendants(panel).first { it.isShown && it.contentDescription ==
                panel.context.getString(dev.zeroinput.ime.ui.R.string.keyboard_tools) }
            tools.isFocusableInTouchMode = true
            assertTrue(tools.requestFocus())
            panel.renderSession(state("ni"))
            assertTrue("Resetting the viewport must not focus a candidate", tools.isFocused)
        }
        instrumentation.waitForIdleSync()
        onMain { assertTrue("Resetting the viewport must not focus a candidate", tools.isFocused) }
    }

    @Test fun newInputCancelsHorizontalFlingAndStaysAtFirstCandidate() = withPanel { panel ->
        onMain { panel.renderSession(state("n")) }
        instrumentation.waitForIdleSync()
        onMain {
            val strip = strip(panel)
            strip.scrollTo(400, 0)
            assertTrue(strip.scrollX > 0)
            strip.fling(5000)
            panel.renderSession(state("ni"))
        }
        // Observe animation frames after the render, not only the immediate scrollTo.
        SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
        onMain { assertEquals(0, strip(panel).scrollX) }
    }

    @Test fun newExpandedInputKeepsResetThroughRefreshBeforeLayout() = withPanel { panel ->
        onMain {
            panel.renderSession(state("n"))
            panel.setCandidatesExpanded(true)
        }
        instrumentation.waitForIdleSync()
        onMain { grid(panel).scrollBy(0, 500) }
        onMain {
            val manager = grid(panel).layoutManager as GridLayoutManager
            assertTrue(manager.findFirstVisibleItemPosition() > 0)
            panel.renderSession(state("ni"))
            val update = state("ni").snapshot
            // A second delivery can arrive before RecyclerView lays out the new input.
            panel.renderSession(InputSessionState(snapshot = update.copy(
                candidates = listOf(Candidate("ni:extra", "新增")) + update.candidates,
            )))
        }
        instrumentation.waitForIdleSync()
        onMain { assertAtStart(grid(panel)) }
    }

    @Test fun compositionChangeResetsBothViewportsEvenWhenRawInputIsUnchanged() = withPanel { panel ->
        onMain { panel.renderSession(state("nihao")) }
        instrumentation.waitForIdleSync()
        onMain {
            strip(panel).scrollTo(400, 0)
            assertTrue(strip(panel).scrollX > 0)
            panel.renderSession(state("nihao").let { it.copy(snapshot = it.snapshot.copy(composition = "ni hao")) })
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(0, strip(panel).scrollX)
            panel.setCandidatesExpanded(true)
        }
        instrumentation.waitForIdleSync()
        onMain {
            val grid = grid(panel)
            grid.scrollBy(0, 500)
            assertTrue((grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0)
            assertTrue(grid.fling(0, 2500))
            panel.renderSession(state("nihao"))
            assertEquals(RecyclerView.SCROLL_STATE_IDLE, grid.scrollState)
        }
        instrumentation.waitForIdleSync()
        onMain { assertAtStart(grid(panel)) }
    }

    @Test fun sameInputAppendPreservesExpandedPositionAndReopeningStartsAtFirstCandidate() = withPanel { panel ->
        onMain {
            panel.renderSession(state("ni"))
            panel.setCandidatesExpanded(true)
        }
        instrumentation.waitForIdleSync()
        var position = -1
        var offset = 0
        onMain {
            val grid = grid(panel)
            grid.scrollBy(0, 500)
            val manager = grid.layoutManager as GridLayoutManager
            position = manager.findFirstVisibleItemPosition()
            offset = checkNotNull(manager.findViewByPosition(position)).top
            assertTrue(position > 0)
            val snapshot = state("ni").snapshot
            panel.renderSession(InputSessionState(snapshot = snapshot.copy(
                candidates = snapshot.candidates + Candidate("ni:extra", "新增"),
            )))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val manager = grid(panel).layoutManager as GridLayoutManager
            assertEquals(position, manager.findFirstVisibleItemPosition())
            assertEquals(offset, checkNotNull(manager.findViewByPosition(position)).top)
            panel.setCandidatesExpanded(false)
            panel.setCandidatesExpanded(true)
        }
        instrumentation.waitForIdleSync()
        onMain { assertAtStart(grid(panel)) }
    }

    private fun state(input: String) = InputSessionState(snapshot = EngineSnapshot(input, input,
        List(48) { Candidate("$input:$it", "公开候选$it") }))

    private fun strip(panel: ZeroInputView) = descendants(panel).filterIsInstance<HorizontalScrollView>().single { it.isShown }
    private fun grid(panel: ZeroInputView) = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }

    private fun assertAtStart(grid: RecyclerView) {
        val manager = grid.layoutManager as GridLayoutManager
        assertEquals(0, manager.findFirstVisibleItemPosition())
        assertEquals(0, checkNotNull(manager.findViewByPosition(0)).top)
    }

    private fun withPanel(test: (ZeroInputView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            KeyboardPreviewFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try {
            instrumentation.waitForIdleSync()
            onMain {
                activity.keyboard.layoutParams = activity.keyboard.layoutParams.apply {
                    width = (360 * activity.resources.displayMetrics.density).toInt()
                }
            }
            test(activity.keyboard)
        } finally { onMain { activity.keyboard.release(); activity.finish() } }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
