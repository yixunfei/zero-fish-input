package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.KeyboardLayoutHost
import dev.zeroinput.ime.ui.KeyboardPlacement
import dev.zeroinput.ime.ui.KeyboardPlacementMode
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class PanelInteractionRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun internalLanguageSurvivesExternalRendersAndClosingSearchRestoresTheEditorLanguage() = onMain {
        val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput_InputMethod)
        val panel = ZeroInputView(context)
        val editor = InputSessionState(language = dev.zeroinput.engine.api.InputLanguage.CHINESE)
        panel.renderSession(editor)
        click(panel, UiR.string.expression_smileys)
        click(panel, UiR.string.expression_search_input)
        panel.renderSearchDraft("test", InputSessionState(language = dev.zeroinput.engine.api.InputLanguage.ENGLISH))
        panel.renderSession(editor)
        fun keyLabels() = descendants(panel).filterIsInstance<dev.zeroinput.ime.ui.KeyboardPanel>()
            .flatMap(::descendants).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue("External updates must not replace the search language", "En" in keyLabels())
        click(panel, UiR.string.expression_close_search)
        assertTrue("Closing search restores the external editor language", "中" in keyLabels())
        panel.release()
    }

    @Test fun handwritingAndExpressionExpansionEscapesThePreviousCollapsedWindow() = onMain {
        val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput_InputMethod)
        for (mode in KeyboardPlacementMode.entries) {
            val panel = ZeroInputView(context)
            val host = KeyboardLayoutHost(context, panel)
            host.applyPlacement(KeyboardPlacement(mode))
            measure(host)
            click(panel, UiR.string.handwriting_open)
            measure(host)
            val compact = panel.height
            click(panel, UiR.string.handwriting_fullscreen)
            measure(host)
            assertTrue("Fullscreen must enlarge the actual writing surface", panel.height > compact + dp(panel, 150))
            assertTrue(panel.height >= dp(panel, 700))
            assertTrue(host.inputBounds().bottom <= host.height)
            click(panel, UiR.string.handwriting_exit_fullscreen)
            measure(host)
            assertEquals(compact, panel.height)
            click(panel, UiR.string.expression_smileys)
            measure(host)
            val small = panel.height
            click(panel, UiR.string.panel_expand)
            measure(host)
            assertTrue(panel.height > small)
            assertTrue(panel.height >= dp(panel, 400))
            val action = find(panel, UiR.string.panel_collapse)
            val rect = Rect(0, 0, action.width, action.height)
            panel.offsetDescendantRectToMyCoords(action, rect)
            assertTrue(rect.right <= panel.width && rect.bottom <= panel.height)
            click(panel, UiR.string.panel_collapse)
            measure(host)
            assertEquals(small, panel.height)
            host.release()
            panel.release()
        }
    }

    @Test fun candidatesPrefetchAndAppendWithoutRefreshingOrJumpingTheViewport() = withAttachedPanel { panel ->
        var count = 8
        var requests = 0
        fun snapshot() = EngineSnapshot("ni", "ni", List(count) { Candidate("row:$it", "候选$it") }, hasNextPage = count < 80)
        lateinit var grid: RecyclerView
        var fullRefreshes = 0
        onMain {
            panel.renderSession(InputSessionState(snapshot = snapshot()))
            panel.onCandidatePageChanged = { direction ->
                assertEquals(PageDirection.NEXT, direction)
                requests++
                count += 8
                panel.renderSession(InputSessionState(snapshot = snapshot()))
            }
            click(panel, UiR.string.expand_candidates)
            grid = descendants(panel).filterIsInstance<RecyclerView>().first { it.isShown }
            checkNotNull(grid.adapter).registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { fullRefreshes++ }
            })
        }
        await { count >= 16 && grid.childCount > 0 }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(requests > 0)
            assertEquals(0, fullRefreshes)
            val layout = grid.layoutManager as GridLayoutManager
            assertEquals("Prefetch must not jump to the appended page", 0, layout.findFirstVisibleItemPosition())
            val position = layout.findFirstVisibleItemPosition()
            val top = checkNotNull(layout.findViewByPosition(position)).top
            count += 8
            panel.renderSession(InputSessionState(snapshot = snapshot()))
            grid.measure(View.MeasureSpec.makeMeasureSpec(grid.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(grid.height, View.MeasureSpec.EXACTLY))
            grid.layout(grid.left, grid.top, grid.right, grid.bottom)
            assertEquals(position, layout.findFirstVisibleItemPosition())
            assertEquals(top, checkNotNull(layout.findViewByPosition(position)).top)
            assertEquals(0, fullRefreshes)
            grid.fling(0, 1800)
            assertEquals(RecyclerView.SCROLL_STATE_SETTLING, grid.scrollState)
            count += 8
            panel.renderSession(InputSessionState(snapshot = snapshot()))
            assertEquals("Appending during a fling must retain its motion", RecyclerView.SCROLL_STATE_SETTLING, grid.scrollState)
        }
        await { (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0 }
    }

    private fun withAttachedPanel(action: (ZeroInputView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try { instrumentation.waitForIdleSync(); action(activity.keyboard) }
        finally { onMain { activity.keyboard.release(); activity.finish() } }
    }

    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, 411), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view, 800), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun click(view: View, id: Int) { find(view, id).performClick() }
    private fun find(view: View, id: Int) = descendants(view).first {
        it.visibility == View.VISIBLE && it.contentDescription == view.context.getString(id)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(40)
        }
        fail("Public panel fixture did not reach the expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
