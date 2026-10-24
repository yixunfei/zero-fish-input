package dev.zeroinput.ime

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.settings.UserDictionaryAdapter
import dev.zeroinput.ime.settings.UserDictionaryTransferDialogs
import dev.zeroinput.userdata.UserTerm
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDictionaryPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun transfersOnlyProceedAfterAnExplicitConfirmation() {
        for (export in listOf(true, false)) {
            val confirmed = AtomicInteger()
            for (button in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
                onMain {
                    val context = context(Locale.ENGLISH, nightMode(), 320, 600)
                    val dialog = if (export) UserDictionaryTransferDialogs.export(context) { confirmed.incrementAndGet() }
                        else UserDictionaryTransferDialogs.import(context) { confirmed.incrementAndGet() }
                    dialog.create()
                    assertEquals(0, confirmed.get())
                    dialog.getButton(button).performClick()
                }
                instrumentation.waitForIdleSync()
                assertEquals(if (button == AlertDialog.BUTTON_POSITIVE) 1 else 0, confirmed.get())
            }
        }
    }

    @Test
    fun transferWarningsAndActionsFitBothLanguagesThemesAndOrientations() = onMain {
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            val night = nightMode()
            for ((width, height) in listOf(320 to 600, 411 to 800, 800 to 320)) {
                for (export in listOf(true, false)) {
                    val context = context(locale, night, width, height)
                    assertTrue(ColorUtils.calculateContrast(
                        context.getColor(R.color.zero_on_surface), context.getColor(R.color.zero_surface),
                    ) >= 4.5)
                    val dialog = if (export) UserDictionaryTransferDialogs.export(context) {}
                        else UserDictionaryTransferDialogs.import(context) {}
                    dialog.create()
                    val root = checkNotNull(dialog.window).decorView
                    measure(root, width, height)
                    for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
                        val button = dialog.getButton(id)
                        assertTrue(button.width > 0 && button.height > 0)
                        assertTrue(button.layout.getEllipsisCount(0) == 0)
                        assertTrue(button.width - button.compoundPaddingLeft - button.compoundPaddingRight >=
                            button.paint.measureText(button.text.toString()))
                    }
                    assertTrue(checkNotNull(dialog.findViewById<TextView>(android.R.id.message)).text.isNotBlank())
                    val title = checkNotNull(dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle))
                    val surface = context.getColor(R.color.zero_surface)
                    assertTrue(ColorUtils.calculateContrast(title.currentTextColor, surface) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor, surface) >= 4.5)
                    if (export && width == 320) saveFixture(root, "dictionary-export-${locale.language}-$night.png")
                    dialog.dismiss()
                }
            }
        }
    }

    @Test
    fun longPhraseRowsKeepDeletionUsableAndReleaseRetiredContent() = onMain {
        val context = context(Locale.ENGLISH, nightMode(), 320, 600)
        val adapter = UserDictionaryAdapter {}
        val holder = adapter.onCreateViewHolder(LinearLayout(context), 0)
        val phrase = UserTerm("fixture", "code".repeat(16), "phrase".repeat(21), InputLanguage.ENGLISH, 1, 0)
        adapter.submit(listOf(phrase))
        adapter.onBindViewHolder(holder, 0)
        holder.itemView.setBackgroundColor(context.getColor(R.color.zero_surface))
        measure(holder.itemView, 320, 64)
        assertTrue(holder.value.height <= holder.itemView.height)
        assertTrue(holder.details.height + holder.value.height <= holder.itemView.height)
        assertTrue(holder.remove.width >= (48 * context.resources.displayMetrics.density).toInt())
        assertTrue(holder.remove.right <= holder.itemView.width)
        assertTrue(checkNotNull(holder.remove.drawable).bounds.width() > 0)
        saveFixture(holder.itemView, "dictionary-row.png")
        adapter.onViewRecycled(holder)
        assertTrue(holder.value.text.isEmpty() && holder.details.text.isEmpty())
        assertTrue(!holder.remove.hasOnClickListeners())
    }

    private fun context(locale: Locale, night: Boolean, width: Int, height: Int): ContextThemeWrapper {
        val target = instrumentation.targetContext
        val config = Configuration(target.resources.configuration).apply {
            setLocale(locale)
            screenWidthDp = width
            screenHeightDp = height
            orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            uiMode = Configuration.UI_MODE_TYPE_NORMAL or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(target.createConfigurationContext(config), R.style.Theme_ZeroInput).apply {
            theme.setTo(resources.newTheme().apply { applyStyle(R.style.Theme_ZeroInput, true) })
        }
    }

    private fun nightMode(): Boolean {
        val actual = instrumentation.targetContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        InstrumentationRegistry.getArguments().getString("expectedNight")?.let {
            assertEquals(it.toBooleanStrict(), actual)
        }
        return actual
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun saveFixture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            descendants(view).forEach { it.viewTreeObserver.dispatchOnPreDraw() }
            view.draw(Canvas(bitmap))
            val root = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(root, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun descendants(view: View): List<View> = if (view is ViewGroup) {
        listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else listOf(view)

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
