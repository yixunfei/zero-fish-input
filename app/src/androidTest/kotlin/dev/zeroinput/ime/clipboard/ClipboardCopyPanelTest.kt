package dev.zeroinput.ime.clipboard

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.R
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.ui.SecureClipboardPanelView
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ClipboardCopyPanelTest {
    @Test fun failedListIsDistinctFromEmptyAndCannotOfferOldRows() = Device.onMain {
        val context = ContextThemeWrapper(Device.context, R.style.Theme_ZeroInput)
        val panel = SecureClipboardPanelView(context)
        val failure = context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_load_failed)
        val empty = context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_empty)
        val items = listOf(dev.zeroinput.ime.ui.SecureClipboardItemUi("fixture", "public row"))
        panel.render(true, items, failed = true)
        fun texts() = descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(failure in texts())
        assertFalse(empty in texts())
        assertFalse("public row" in texts())
        panel.renderPasteConfirmation(true)
        panel.renderPasteConfirmation(false)
        assertTrue(failure in texts())
        panel.render(false, emptyList(), failed = true)
        assertFalse(failure in texts())
        panel.render(true, emptyList())
        assertTrue(empty in texts())
        panel.render(true, items)
        assertTrue("public row" in texts())
    }

    @Test fun failureMessageFitsSmallPortraitAndLandscapeInBothThemesAndLanguages() = Device.onMain {
        for (night in listOf(false, true)) for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            for ((width, height) in listOf(320 to 220, 800 to 180)) {
                val configuration = Configuration(Device.context.resources.configuration).apply {
                    setLocale(locale)
                    fontScale = 1.3f
                    orientation = if (width > height * 2) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    uiMode = Configuration.UI_MODE_TYPE_NORMAL or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(Device.context.createConfigurationContext(configuration), R.style.Theme_ZeroInput)
                val panel = SecureClipboardPanelView(context)
                panel.render(true, emptyList(), failed = true)
                val density = panel.resources.displayMetrics.density
                panel.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val message = descendants(panel).filterIsInstance<TextView>().single {
                    it.text == context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_load_failed)
                }
                assertTrue("Failure message must not clip", message.layout.height <= message.height - message.compoundPaddingTop - message.compoundPaddingBottom)
                saveFixture(panel, "clipboard-failure-${locale.language}-$night-$width.png")
            }
        }
    }

    @Test
    fun copyActionStaysAtTopAndFitsSmallLandscapeAndLargePanelsInBothThemesAndLanguages() = Device.onMain {
        for (night in listOf(false, true)) for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            for ((width, height) in listOf(320 to 220, 800 to 180, 411 to 280)) {
                val configuration = Configuration(Device.context.resources.configuration).apply {
                    setLocale(locale)
                    fontScale = 1.3f
                    uiMode = Configuration.UI_MODE_TYPE_NORMAL or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(Device.context.createConfigurationContext(configuration), R.style.Theme_ZeroInput)
                val panel = SecureClipboardPanelView(context)
                var copies = 0
                panel.onCopySelectionRequested = { copies++ }
                panel.render(false, emptyList())
                panel.renderCopyAvailable(true)
                val density = panel.resources.displayMetrics.density
                panel.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val copy = descendants(panel).filterIsInstance<MaterialButton>().first()
                assertTrue(copy.top >= 0 && copy.bottom <= panel.height)
                assertTrue(copy.height >= (48 * density).toInt())
                for (line in 0 until copy.layout.lineCount) {
                    assertEquals(0, copy.layout.getEllipsisCount(line))
                    assertTrue(copy.layout.getLineWidth(line) <= copy.width - copy.compoundPaddingLeft - copy.compoundPaddingRight)
                }
                copy.performClick()
                assertEquals(1, copies)
                panel.renderCopyAvailable(false)
                assertFalse(copy.isEnabled)
                panel.renderCopyAvailable(true)
                if (width == 320) saveFixture(panel, "copy-panel-${locale.language}-$night.png")
                panel.renderPasteConfirmation(true)
                panel.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val commands = descendants(panel).filterIsInstance<MaterialButton>()
                assertEquals(2, commands.size)
                commands.forEach { assertTrue(it.height >= (48 * density).toInt()) }
                if (width == 320) saveFixture(panel, "paste-panel-${locale.language}-$night.png")
            }
        }
    }

    private fun saveFixture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.setBackgroundColor(view.context.getColor(R.color.zero_surface))
            descendants(view).forEach { it.viewTreeObserver.dispatchOnPreDraw() }
            view.draw(Canvas(bitmap))
            File(checkNotNull(Device.context.getExternalFilesDir(null)), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
