package dev.zeroinput.ime

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.ui.CandidateStripView
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.ime.ui.ExpandedCandidateAppearance
import dev.zeroinput.ime.ui.ExpandedCandidateDensity
import dev.zeroinput.ime.ui.ExpandedCandidateLayout
import dev.zeroinput.ime.ui.KeyboardAppearance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class CandidateDensityTest {
    @Test fun readableDefaultsAndDensityChoicesStayBoundedAcrossWidthsAndThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (widthDp in listOf(240, 360, 720)) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = android.view.ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(configuration), R.style.Theme_ZeroInput_InputMethod,
                )
                val panel = ZeroInputView(context)
                try {
                    panel.renderSession(InputSessionState(snapshot = EngineSnapshot("ni", "ni",
                        List(30) { Candidate("public:$it", listOf("你", "呢", "泥", "拟", "逆")[it % 5]) })))
                    val density = context.resources.displayMetrics.density
                    val width = (widthDp * density).toInt()
                    fun measure() {
                        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST))
                        panel.layout(0, 0, width, panel.measuredHeight)
                    }
                    measure()
                    val strip = descendants(panel).filterIsInstance<CandidateStripView>().single()
                    assertFalse(descendants(strip).any {
                        it.visibility == View.VISIBLE && it.contentDescription == context.getString(UiR.string.candidate_more_actions)
                    })
                    descendants(strip).first { it.contentDescription == context.getString(UiR.string.expand_candidates) }.performClick()
                    repeat(3) { measure() }
                    val grid = descendants(panel).filterIsInstance<RecyclerView>().single {
                        (it.parent as? View)?.javaClass?.simpleName == "ExpandedCandidatesView"
                    }
                    val counts = ExpandedCandidateDensity.entries.map { mode ->
                        panel.applyAppearance(KeyboardAppearance(expandedCandidates = ExpandedCandidateAppearance(density = mode)))
                        repeat(3) { measure() }
                        val firstRow = (0 until grid.childCount).map { grid.getChildAt(it) }.filter { it.top == 0 }
                        assertEquals(width, firstRow.sumOf { it.width })
                        assertTrue(firstRow.all { it.height >= (48 * density).toInt() && it.right <= width })
                        firstRow.size
                    }
                    assertTrue("Spacious rows must reduce short-word crowding", counts[0] > counts[2])
                    assertTrue(counts[0] >= counts[1] && counts[1] >= counts[2])
                    panel.applyAppearance(KeyboardAppearance(expandedCandidates = ExpandedCandidateAppearance(
                        layout = ExpandedCandidateLayout.LIST, textSize = 24)))
                    repeat(3) { measure() }
                    assertTrue((0 until grid.childCount).all { grid.getChildAt(it).width == width })
                    panel.applyAppearance(KeyboardAppearance())
                    repeat(3) { measure() }
                    val bitmap = Bitmap.createBitmap(width, panel.height, Bitmap.Config.ARGB_8888)
                    try {
                        panel.draw(Canvas(bitmap))
                        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "candidate-density").apply { mkdirs() }
                        java.io.File(folder, "${widthDp}-${if (night) "dark" else "light"}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                } finally { panel.release() }
            }
        }
    }

    @Test fun longWordsWrapAndSelectionAndScrollAnchorSurviveAppearanceChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput_InputMethod)
            val panel = ZeroInputView(context)
            try {
                val candidates = List(30) { Candidate("public:$it", if (it % 2 == 0) "公开长候选示例用于检查换行显示" else "山水") }
                panel.renderSession(InputSessionState(snapshot = EngineSnapshot("test", "test", candidates)))
                val width = (240 * context.resources.displayMetrics.density).toInt()
                fun measure() {
                    panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
                    panel.layout(0, 0, width, panel.measuredHeight)
                }
                measure()
                panel.setCandidatesExpanded(true)
                repeat(3) { measure() }
                val grid = descendants(panel).filterIsInstance<RecyclerView>().single {
                    (it.parent as? View)?.javaClass?.simpleName == "ExpandedCandidatesView"
                }
                grid.scrollBy(0, (200 * context.resources.displayMetrics.density).toInt())
                val manager = grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                val anchor = manager.findFirstVisibleItemPosition()
                val offset = checkNotNull(manager.findViewByPosition(anchor)).top
                panel.applyAppearance(KeyboardAppearance(expandedCandidates = ExpandedCandidateAppearance(
                    layout = ExpandedCandidateLayout.LIST, textSize = 24, separators = false)))
                repeat(3) { measure() }
                assertEquals(anchor, manager.findFirstVisibleItemPosition())
                assertEquals(offset, checkNotNull(manager.findViewByPosition(anchor)).top)
                val cell = grid.getChildAt(0) as android.widget.TextView
                assertEquals(width, cell.width)
                assertTrue(cell.layout.lineCount > 1 || cell.text == "山水")
                var chosen = -1
                panel.onCandidateSelected = { index, _ -> chosen = index }
                cell.performClick()
                assertEquals(anchor, chosen)
                panel.renderSession(InputSessionState())
                assertEquals(View.GONE, (grid.parent as View).visibility)
            } finally { panel.release() }
        }
    }

    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
