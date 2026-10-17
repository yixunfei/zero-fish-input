package dev.zeroinput.ime

import android.content.Intent
import android.view.View
import android.view.ViewGroup
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

/** Public fixtures exercise eviction, not just appending to an unchanged first page. */
@RunWith(AndroidJUnit4::class)
class CandidateWindowContinuityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val candidates = List(96) { Candidate("public:$it", ('一'.code + it).toChar().toString()) }

    @Test fun evictingPartOfARowKeepsEveryVisibleCandidateInPlace() = withPanel { panel ->
        val before = onMain { visible(panel, 0) }
        onMain { panel.renderSession(state(5)) }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(before, visible(panel, 5)) }
    }

    @Test fun consecutiveWindowDeliveriesBeforeLayoutKeepTheOriginalAnchor() = withPanel { panel ->
        val before = onMain { visible(panel, 0) }
        onMain {
            panel.renderSession(state(5))
            panel.renderSession(state(10))
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(before, visible(panel, 10)) }
    }

    @Test fun unchangedRefreshDoesNotCancelThePostedEdgeRequest() = withPanel { panel ->
        var requests = 0
        val next = state(0).let { it.copy(snapshot = it.snapshot.copy(hasNextPage = true)) }
        onMain {
            panel.onCandidatePageChanged = { requests++ }
            panel.renderSession(next)
        }
        instrumentation.waitForIdleSync()
        onMain {
            requests = 0
            grid(panel).scrollBy(0, 10_000)
            panel.renderSession(next)
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(1, requests) }
    }

    @Test fun prependingBeforeTheViewportPreservesVisibleRowsAndSelection() = withPanel(10) { panel ->
        val before = onMain { visible(panel, 10) }
        onMain { panel.renderSession(state(5)) }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(before, visible(panel, 5))
            val grid = grid(panel)
            val position = (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
            var selected: Pair<Int, String?>? = null
            panel.onCandidateSelected = { index, id -> selected = index to id }
            checkNotNull(grid.findViewHolderForAdapterPosition(position)).itemView.performClick()
            assertEquals(position to candidates[5 + position].id, selected)
        }
    }

    private data class Cell(val id: String, val left: Int, val top: Int, val width: Int, val height: Int)

    private fun visible(panel: ZeroInputView, start: Int): List<Cell> {
        val grid = grid(panel)
        return (0 until grid.childCount).map { grid.getChildAt(it) }
            .filter { it.bottom > 0 && it.top < grid.height }
            .map { Cell(candidates[start + grid.getChildAdapterPosition(it)].id, it.left, it.top, it.width, it.height) }
    }

    private fun state(start: Int) = InputSessionState(snapshot = EngineSnapshot("ni", "ni",
        candidates.subList(start, start + 48)))

    private fun withPanel(start: Int = 0, test: (ZeroInputView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            KeyboardPreviewFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try {
            onMain {
                activity.keyboard.layoutParams = activity.keyboard.layoutParams.apply {
                    width = (360 * activity.resources.displayMetrics.density).toInt()
                }
                activity.keyboard.renderSession(state(start))
                activity.keyboard.setCandidatesExpanded(true)
            }
            instrumentation.waitForIdleSync()
            onMain {
                val grid = grid(activity.keyboard)
                (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(18, -15)
            }
            instrumentation.waitForIdleSync()
            onMain { assertTrue((grid(activity.keyboard).layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 10) }
            test(activity.keyboard)
        } finally { onMain { activity.keyboard.release(); activity.finish() } }
    }

    private fun grid(panel: ZeroInputView) = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
