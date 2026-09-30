package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.EmojiCatalog
import dev.zeroinput.ime.ui.EmojiEntry
import dev.zeroinput.ime.ui.EmojiPanelView
import dev.zeroinput.ime.ui.PersonalExpressionsUi
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

/** Public fixtures only. This test deliberately runs on API 26 as well as newer Android. */
@RunWith(AndroidJUnit4::class)
class RgiEmojiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun preparePublicCatalogOnItsWorker() {
        val latch = CountDownLatch(1)
        var ready = false
        var mainCallback = false
        lateinit var handle: Closeable
        onMain {
            handle = EmojiCatalog.prepare(instrumentation.targetContext) {
                ready = it
                mainCallback = Looper.myLooper() == Looper.getMainLooper()
                latch.countDown()
            }
        }
        try {
            assertTrue("Emoji background preparation timed out", latch.await(15, TimeUnit.SECONDS))
            assertTrue(ready)
            assertTrue(mainCallback)
        } finally { handle.close() }
    }

    @Test fun preparedPublicCatalogHasEveryRgiSequenceAndComponent() {
        assertTrue(EmojiCatalog.isReady)
        assertEquals("18.0", EmojiCatalog.unicodeVersion)
        val entries = EmojiCatalog.entries.filter { it.artworkKey != null }
        assertEquals(3972, entries.size)
        assertEquals(3963, entries.count { !it.isComponent })
        assertEquals(9, entries.count { it.isComponent })
        assertEquals(3972, entries.map { it.value }.distinct().size)
        assertEquals(15, EmojiCatalog.maximumEmojiUtf16Length)
    }

    @Test fun newestZwjToneCountryAndSubdivisionCellsRenderBundledArtAndSubmitExactText() = withPanel { panel ->
        val newest = String(Character.toChars(0x1FAEB))
        val values = listOf(newest, "👩🏽‍🚀", "🫱🏻‍🫲🏿", "🇨🇳", "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F")
        var selected: String? = null
        onMain { panel.onEmojiSelected = { selected = it.value }; click(panel, UiR.string.expression_search) }
        for (value in values) {
            val entry = requireNotNull(EmojiCatalog.find(value))
            val source = instrumentation.targetContext.assets.open("emoji/18.0/images/${entry.artworkKey}.webp")
                .use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try {
                search(panel, value)
                await { cell(panel, entry)?.text?.isEmpty() == true }
                onMain {
                    val cell = requireNotNull(cell(panel, entry))
                    assertTrue(cell.width >= dp(cell, 48) && cell.height >= dp(cell, 48))
                    assertBundledPixels(cell, source)
                    selected = null
                    cell.performClick()
                    assertEquals(value, selected)
                    assertEquals(value.length, EmojiCatalog.longestEmojiSuffixLength(value))
                }
            } finally { source.recycle() }
        }
    }

    @Test fun restrictedPersonalizationStillOffersAccessibleOfflineSkinToneVariants() = withPanel { panel ->
        val base = requireNotNull(EmojiCatalog.find("👍"))
        val toned = requireNotNull(EmojiCatalog.find("👍🏻"))
        var selected: String? = null
        onMain {
            panel.renderPersonal(false, PersonalExpressionsUi(), emptyList())
            panel.onEmojiSelected = { selected = it.value }
            click(panel, UiR.string.expression_search)
        }
        search(panel, base.value)
        await { cell(panel, base)?.text?.isEmpty() == true }
        onMain { requireNotNull(cell(panel, base)).performLongClick() }
        val variants = panel.context.getString(UiR.string.expression_variants)
        awaitAccessible { clickableAccessible(variants) != null }
        assertNull(findAccessible(panel.context.getString(UiR.string.expression_favorite)))
        assertTrue("Accessible variants menu row rejected click",
            requireNotNull(clickableAccessible(variants)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val label = label(panel, toned)
        val close = panel.context.getString(UiR.string.expression_close_variants)
        awaitAccessible { findAccessible(close) != null && clickableAccessible(label) != null }
        assertTrue("Accessible tone variant rejected click",
            requireNotNull(clickableAccessible(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await { selected == toned.value }
        assertEquals(toned.value, selected)
    }

    @Test fun sessionResetDismissesVariantMenuAndRejectsAnOldCellClick() = withPanel { panel ->
        val entry = requireNotNull(EmojiCatalog.find("👍"))
        var commits = 0
        onMain { panel.onEmojiSelected = { commits++ }; click(panel, UiR.string.expression_search) }
        search(panel, entry.value)
        await { cell(panel, entry)?.text?.isEmpty() == true }
        lateinit var old: TextView
        onMain { old = requireNotNull(cell(panel, entry)); old.performLongClick() }
        val variants = panel.context.getString(UiR.string.expression_variants)
        awaitAccessible { findAccessible(variants) != null }
        onMain { panel.clearSession(); old.performClick(); assertEquals(0, commits) }
        awaitAccessible { findAccessible(variants) == null }
    }

    private fun assertBundledPixels(cell: TextView, source: Bitmap) {
        val actual = Bitmap.createBitmap(cell.width, cell.height, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(cell.width, cell.height, Bitmap.Config.ARGB_8888)
        try {
            cell.draw(Canvas(actual))
            val size = minOf(32 * cell.resources.displayMetrics.density, (cell.width - cell.paddingLeft - cell.paddingRight).toFloat(),
                (cell.height - cell.paddingTop - cell.paddingBottom).toFloat())
            val left = (cell.width - size) / 2
            val top = (cell.height - size) / 2
            Canvas(expected).drawBitmap(source, null, RectF(left, top, left + size, top + size),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            var compared = 0
            for (y in top.toInt() until (top + size).toInt()) for (x in left.toInt() until (left + size).toInt()) {
                val pixel = expected.getPixel(x, y)
                if (Color.alpha(pixel) == 255) {
                    assertEquals("Opaque bundled image pixels must match the rendered cell", pixel, actual.getPixel(x, y))
                    compared++
                }
            }
            assertTrue(compared > 50)
        } finally { actual.recycle(); expected.recycle() }
    }

    private fun search(panel: EmojiPanelView, value: String) {
        onMain { panel.clearQuery(); panel.appendQuery(value) }
    }

    private fun cell(panel: EmojiPanelView, entry: EmojiEntry): TextView? =
        descendants(panel).filterIsInstance<RecyclerView>().single().let { grid ->
            (0 until grid.childCount).map { grid.getChildAt(it) }.filterIsInstance<TextView>()
                .firstOrNull { it.contentDescription?.toString() == label(panel, entry) }
        }

    private fun label(panel: EmojiPanelView, entry: EmojiEntry): String =
        if (panel.resources.configuration.locales[0].language == "zh") entry.name else entry.englishName

    private fun withPanel(action: (EmojiPanelView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        lateinit var panel: EmojiPanelView
        try {
            onMain {
                panel = EmojiPanelView(activity.keyboard.context)
                activity.keyboard.release()
                activity.setContentView(FrameLayout(panel.context).apply {
                    addView(panel, FrameLayout.LayoutParams(-1, dp(panel, 288), Gravity.BOTTOM))
                })
            }
            instrumentation.waitForIdleSync()
            action(panel)
        } finally { onMain { panel.clearSession(); activity.finish() } }
    }

    private fun click(view: View, label: Int) {
        descendants(view).first { it.contentDescription?.toString() == view.context.getString(label) }.performClick()
    }

    private fun findAccessible(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun clickableAccessible(label: String): AccessibilityNodeInfo? {
        var node = findAccessible(label)
        // AppCompat menu labels are children of the actionable ListMenuItemView row.
        // TalkBack delegates activation to that ancestor rather than clicking the title TextView.
        repeat(8) {
            val current = node ?: return null
            if (current.isEnabled && current.isClickable &&
                current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return current
            node = current.parent
        }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Emoji UI did not reach the required state")
    }

    private fun awaitAccessible(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail("Emoji accessibility state did not become available")
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        requireNotNull(result).getOrThrow()
    }
}
