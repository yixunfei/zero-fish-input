package dev.zeroinput.ime

import android.content.res.Configuration
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

/** Only fixed public fixtures are rendered or saved. */
@RunWith(AndroidJUnit4::class)
class InputExperienceVisualTest {
    @Test fun inputPagesKeepTheirGeometryAcrossWidthsAndThemesAndLongCandidatesRemainReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            KeyboardPreviewFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try {
            for (night in listOf(false, true)) for ((width, height) in listOf(240 to 800, 320 to 800, 360 to 800, 411 to 800, 720 to 320)) {
                val source = instrumentation.targetContext
                val config = Configuration(source.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = width
                    screenHeightDp = height
                }
                val context = ContextThemeWrapper(source.createConfigurationContext(config), R.style.Theme_ZeroInput_InputMethod)
                var result: Result<Unit>? = null
                instrumentation.runOnMainSync { result = runCatching {
                    val panel = ZeroInputView(context)
                    activity.setContentView(panel)
                    try {
                        assertTrue(panel.isAttachedToWindow)
                        verifyPages(panel, width, height, if (night) "dark" else "light")
                    } finally { panel.release() }
                } }
                checkNotNull(result).getOrThrow()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun verifyPages(panel: ZeroInputView, width: Int, height: Int, theme: String) {
        fun capture(name: String) {
            repeat(3) { measure(panel, width, height) }
            panel.viewTreeObserver.dispatchOnPreDraw()
            val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
            try {
                panel.draw(Canvas(bitmap))
                val folder = java.io.File(panel.context.getExternalFilesDir(null), "input-experience").apply { mkdirs() }
                java.io.File(folder, "$width-$theme-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
        fun click(id: Int) = descendants(panel).first {
            it.contentDescription == panel.context.getString(id) && visible(it, panel)
        }.performClick()
        capture("letters")
        val originalHeight = panel.height
        click(UiR.string.key_numbers)
        capture("numbers")
        assertEquals(originalHeight, panel.height)
        click(UiR.string.key_symbols)
        capture("symbols")
        assertEquals(originalHeight, panel.height)
        click(UiR.string.key_letters)
        click(UiR.string.keyboard_tools)
        capture("tools")
        assertEquals(originalHeight, panel.height)
        assertTrue(panel.navigateBack())
        val phrase = "这是一段用于验证候选完整展示的公开测试文字".repeat(4)
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("ceshi", "ce shi", listOf(
            Candidate("public:long", phrase), Candidate("public:short", "测试"), Candidate("public:other", "侧视"),
        ), canSelectSyllable = true, selection = dev.zeroinput.engine.api.CompositionSelection(2))))
        capture("candidates")
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("chaxun", "cha xun", listOf(
            Candidate("query", "查询"), Candidate("inspect", "查讯")), canSelectSyllable = true,
            selection = dev.zeroinput.engine.api.CompositionSelection(1, 3))))
        capture("pinyin-selection")
        panel.renderSession(InputSessionState(snapshot = EngineSnapshot("ceshi", "ce shi", listOf(
            Candidate("public:long", phrase), Candidate("public:short", "测试"), Candidate("public:other", "侧视"),
        ), canSelectSyllable = true, selection = dev.zeroinput.engine.api.CompositionSelection(2))))
        click(UiR.string.expand_candidates)
        capture("expanded")
        val grid = descendants(panel).filterIsInstance<RecyclerView>().single { visible(it, panel) }
        val candidate = descendants(grid).filterIsInstance<TextView>().first { it.text.toString() == phrase }
        val layout = checkNotNull(candidate.layout)
        assertEquals(phrase.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
        assertTrue(candidate.height >= layout.height)
        assertTrue(descendants(panel).filterIsInstance<KeyboardPanel>().single().preferredSizeIsPositive())
    }

    private fun KeyboardPanel.preferredSizeIsPositive() = measuredWidth > 0 && measuredHeight > 0
    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visible(view: View, root: View): Boolean = view.visibility == View.VISIBLE &&
        (view === root || (view.parent as? View)?.let { visible(it, root) } == true)
}
