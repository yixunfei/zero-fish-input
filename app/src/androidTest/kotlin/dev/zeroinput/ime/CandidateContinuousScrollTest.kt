package dev.zeroinput.ime

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class CandidateContinuousScrollTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun expandedBackwardBrowsingDoesNotReloadTheOppositeEdge() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            KeyboardPreviewFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        val panel = activity.keyboard
        var first = 80
        val requests = mutableListOf<PageDirection>()
        fun state() = InputSessionState(snapshot = EngineSnapshot("ni", "ni",
            // Short public words keep three page boundaries within the platform's
            // capped fling distance even with spacious rows. Long wrapping has its own test.
            List(48) { Candidate("public:${first + it}", ('一'.code + first + it).toChar().toString()) },
            hasPreviousPage = first > 0, hasNextPage = first < 160))
        try {
            instrumentation.waitForIdleSync()
            onMain {
                // Keep the page geometry stable regardless of the device rotation.
                panel.layoutParams = panel.layoutParams.apply {
                    width = (360 * panel.resources.displayMetrics.density).toInt()
                }
                panel.onCandidatePageChanged = { direction ->
                    requests += direction
                    first += if (direction == PageDirection.PREVIOUS) -5 else 5
                    panel.renderSession(state())
                }
                panel.renderSession(state())
                descendants(panel).first {
                    it.isShown && it.contentDescription == panel.context.getString(UiR.string.expand_candidates)
                }.performClick()
            }
            instrumentation.waitForIdleSync()
            onMain { requests.clear() }
            onMain {
                val strip = descendants(panel).filterIsInstance<android.widget.HorizontalScrollView>()
                    .single { it.isShown }
                strip.scrollTo(strip.getChildAt(0).width, 0)
            }
            instrumentation.waitForIdleSync()
            onMain { assertTrue("Expanded candidate loading belongs to the vertical viewport", requests.isEmpty()) }
            onMain {
                val grid = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
                // Position the viewport without a forward drag that can request the
                // opposite edge before the backward fling under test even begins.
                (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager)
                    .scrollToPositionWithOffset(9, 0)
            }
            instrumentation.waitForIdleSync()
            onMain { requests.clear() }
            onMain {
                val grid = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
                assertTrue(grid.fling(0, -(2000 * grid.resources.displayMetrics.density).toInt()))
            }
            waitForFling(panel)
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue("One backward fling must continue across multiple loaded pages: $requests", requests.count { it == PageDirection.PREVIOUS } >= 3)
                assertFalse("The visible strip must not undo backward loading", PageDirection.NEXT in requests)
            }
            onMain { requests.clear() }
            onMain {
                first = 80
                panel.renderSession(state())
                val grid = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
                // Leave several rows before prefetch so idle setup cannot consume
                // the forward pages that the single fling is meant to traverse.
                (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager)
                    .scrollToPositionWithOffset(checkNotNull(grid.adapter).itemCount - 21, 0)
            }
            instrumentation.waitForIdleSync()
            onMain { requests.clear() }
            onMain {
                val grid = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
                assertTrue(grid.fling(0, (2000 * grid.resources.displayMetrics.density).toInt()))
            }
            waitForFling(panel)
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue("One forward fling must continue across multiple loaded pages: $requests", requests.count { it == PageDirection.NEXT } >= 3)
                assertFalse("Forward loading must not request the opposite edge", PageDirection.PREVIOUS in requests)
            }
        } finally { onMain { panel.release(); activity.finish() } }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun waitForFling(panel: dev.zeroinput.ime.ui.ZeroInputView) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var moving = true
        while (moving && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(50)
            onMain {
                moving = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
                    .scrollState != RecyclerView.SCROLL_STATE_IDLE
            }
        }
        assertFalse("Candidate fling must settle within five seconds", moving)
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
