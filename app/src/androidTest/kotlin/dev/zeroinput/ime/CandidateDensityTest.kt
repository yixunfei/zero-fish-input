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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class CandidateDensityTest {
    @Test fun shortCandidatesFillRowsAcrossWidthsAndThemesWithoutOverflowMenu() {
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
                    val firstRow = (0 until grid.childCount).map { grid.getChildAt(it) }.filter { it.top == 0 }
                    assertTrue("Short candidates must pack densely at $widthDp dp", firstRow.size >= widthDp / 60)
                    assertEquals(width, firstRow.sumOf { it.width })
                    assertTrue(firstRow.all { it.height >= (48 * density).toInt() && it.right <= width })
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

    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
