package dev.zeroinput.ime

import android.content.res.Configuration
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ui.EmojiCategory
import dev.zeroinput.ime.ui.EmojiCatalog
import dev.zeroinput.ime.ui.EmojiEntry
import dev.zeroinput.ime.ui.EmojiPanelView
import dev.zeroinput.ime.ui.KaomojiGroup
import dev.zeroinput.ime.ui.PersonalExpressionsUi
import dev.zeroinput.ime.ui.ZeroInputView
import java.io.File
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class ExpressionPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun awaitPublicCatalogPreparation() {
        val latch = CountDownLatch(1)
        var ready = false
        lateinit var handle: Closeable
        onMain { handle = EmojiCatalog.prepare(instrumentation.targetContext) { ready = it; latch.countDown() } }
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            assertTrue(ready)
        } finally { handle.close() }
    }

    @Test fun kaomojiSubtagsAndLongEntriesFitNarrowWideAndDarkPanels() = onMain {
        for (night in listOf(false, true)) for (width in listOf(320, 411, 800)) {
            val panel = EmojiPanelView(context(night, width))
            panel.renderPersonal(true, PersonalExpressionsUi(), emptyList())
            measure(panel, width, 260)
            click(panel, UiR.string.expression_kaomoji)
            measure(panel, width, 260)
            click(panel, UiR.string.expression_surprised)
            measure(panel, width, 260)
            assertTrue(visible(panel).filterIsInstance<TextView>().any { it.text.toString() == "⊙_⊙" })
            verifyCells(panel)
            val custom = EmojiEntry("( ^_^ )".repeat(16), EmojiCategory.CUSTOM, "fixture", KaomojiGroup.HAPPY, "fixture", "Public fixture")
            panel.renderPersonal(true, PersonalExpressionsUi(listOf(custom)), emptyList())
            click(panel, UiR.string.expression_custom)
            measure(panel, width, 260)
            verifyCells(panel)
            if (width == 320) save(panel, "expressions-${if (night) "dark" else "light"}.png")
        }
    }

    @Test fun revokedPrivateCellsAreClearedAndCannotBeSelectedThroughOldViews() = onMain {
        val panel = EmojiPanelView(context(false, 320))
        val custom = EmojiEntry("(public-fixture)", EmojiCategory.CUSTOM, "fixture", KaomojiGroup.HAPPY, "fixture", "Public")
        var selected = false
        panel.onEmojiSelected = { selected = true }
        panel.renderPersonal(true, PersonalExpressionsUi(listOf(custom), setOf(custom.value)), listOf(custom.value))
        measure(panel, 320, 260)
        click(panel, UiR.string.expression_custom)
        measure(panel, 320, 260)
        val old = visible(panel).filterIsInstance<TextView>().single { it.text.toString() == custom.value }
        panel.renderPersonal(false, PersonalExpressionsUi(), emptyList())
        assertEquals("", old.text.toString())
        old.performClick()
        assertFalse(selected)
        measure(panel, 320, 260)
        assertTrue(visible(panel).filterIsInstance<TextView>().any { it.text.toString() == panel.context.getString(UiR.string.expression_private) })
    }

    @Test fun reusedCellRejectsAGestureStartedBeforeTheListChanged() = onMain {
        val panel = EmojiPanelView(context(false, 320))
        var selected = false
        panel.onEmojiSelected = { selected = true }
        measure(panel, 320, 260)
        val grid = visible(panel).filterIsInstance<RecyclerView>().single()
        val holder = checkNotNull(grid.findViewHolderForAdapterPosition(0))
        val cell = holder.itemView
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 16f, 16f, 0).also {
            cell.dispatchTouchEvent(it)
            it.recycle()
        }
        click(panel, UiR.string.expression_people)
        @Suppress("UNCHECKED_CAST")
        (grid.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>).bindViewHolder(holder, 0)
        assertFalse(cell.performLongClick())
        cell.performClick()
        assertFalse(selected)
    }

    @Test fun searchAndSubtagsKeepTheKeyboardInsideTheMeasuredWindow() {
        for (width in listOf(320, 411, 800)) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
            lateinit var panel: ZeroInputView
            lateinit var expressions: EmojiPanelView
            try {
                onMain {
                    panel = ZeroInputView(context(false, width))
                    activity.keyboard.release()
                    activity.setContentView(panel)
                    panel.renderExpressions(true, PersonalExpressionsUi(), emptyList())
                    measure(panel, width, null)
                    click(panel, UiR.string.expression_smileys)
                    click(panel, UiR.string.expression_kaomoji)
                    click(panel, UiR.string.expression_search)
                    measure(panel, width, null)
                    expressions = visible(panel).filterIsInstance<EmojiPanelView>().single()
                    expressions.appendQuery("kaixin")
                }
                await { visible(expressions).filterIsInstance<RecyclerView>().single().adapter?.itemCount?.let { it > 0 } == true }
                onMain {
                    measure(panel, width, null)
                    assertTrue(expressions.height >= dp(panel, 224))
                    assertTrue(panel.height < dp(panel, if (width == 800) 411 else 700))
                    assertTrue(visible(expressions).filterIsInstance<TextView>().any { it.text.toString() == "^_^" })
                    verifyCells(expressions)
                    save(panel, "expression-search-$width.png")
                }
            } finally { onMain { panel.release(); activity.finish() } }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Expression background search did not complete")
    }

    private fun context(night: Boolean, width: Int): android.content.Context {
        val target = instrumentation.targetContext
        val config = Configuration(target.resources.configuration).apply {
            screenWidthDp = width
            screenHeightDp = if (width == 800) 411 else 914
            orientation = if (width == 800) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        }
        return ContextThemeWrapper(target.createConfigurationContext(config), R.style.Theme_ZeroInput_InputMethod)
    }

    private fun measure(view: View, width: Int, height: Int?) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view, height ?: 1000), if (height == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun verifyCells(panel: View) {
        val grid = visible(panel).filterIsInstance<RecyclerView>().single()
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i) as TextView
            assertTrue(cell.left >= 0 && cell.right <= grid.width)
            val layout = checkNotNull(cell.layout)
            assertEquals(cell.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(cell.height >= layout.height + cell.compoundPaddingTop + cell.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
        }
    }

    private fun save(view: View, name: String) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "expression-fixtures").apply { mkdirs() }
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun click(root: View, label: Int) {
        visible(root).first { it.contentDescription?.toString() == root.context.getString(label) }.performClick()
    }
    private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
