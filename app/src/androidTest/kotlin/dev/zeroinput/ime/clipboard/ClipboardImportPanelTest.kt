package dev.zeroinput.ime.clipboard

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.R
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardImportPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun reviewAndConfirmationFitSmallScreensBothThemesAndLanguages() = onMain {
        for (night in listOf(false, true)) {
            for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                for ((width, height) in listOf(320 to 600, 800 to 320)) {
                    val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                        setLocale(locale)
                        uiMode = Configuration.UI_MODE_TYPE_NORMAL or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        fontScale = 1.3f
                    }
                    val context = ContextThemeWrapper(
                        instrumentation.targetContext.createConfigurationContext(configuration), R.style.Theme_ZeroInput,
                    )
                    val view = ClipboardImportView(context)
                    for (authorized in listOf(false, true)) {
                        if (authorized) view.showAuthorized("Public fixture text. ".repeat(390))
                        else view.showReview(8_192, enabled = false)
                        measure(view, width, height)
                        val buttons = children(view).filterIsInstance<MaterialButton>()
                            .filter { it.visibility == View.VISIBLE }
                        assertEquals(if (authorized) 1 else 2, buttons.size)
                        buttons.forEach { button ->
                            assertTrue(button.width > 0 && button.height >= dp(view, 48))
                            assertTrue(button.bottom <= view.height && button.top >= dp(view, 56))
                            for (line in 0 until button.layout.lineCount) {
                                assertEquals(0, button.layout.getEllipsisCount(line))
                                assertTrue(button.layout.getLineWidth(line) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight)
                            }
                        }
                        if (authorized) saveFixture(view, "import-${locale.language}-$night-$width.png")
                    }
                    view.clearText()
                    assertFalse(children(view).filterIsInstance<TextView>().any { it.text.contains("Public fixture") })
                }
            }
        }
    }

    @Test
    fun externalLaunchClearsIntentAndNeverRestoresDraftOrWritesWithoutConfirmation() {
        val target = instrumentation.targetContext
        val intent = Intent(target, ClipboardImportActivity::class.java)
            .setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "public activity fixture")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ClipboardImportActivity
        try {
            onMain {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(activity.intent.extras == null)
                val root = activity.findViewById<View>(android.R.id.content)
                assertFalse(children(root).filterIsInstance<TextView>().any { it.text.contains("public activity fixture") })
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                assertTrue(state.isEmpty)
                instrumentation.callActivityOnNewIntent(activity, intent)
                assertTrue(activity.isFinishing)
            }
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view, height), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun saveFixture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            children(view).forEach { it.viewTreeObserver.dispatchOnPreDraw() }
            view.setBackgroundColor(view.context.getColor(R.color.zero_surface))
            view.draw(Canvas(bitmap))
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun children(view: View): List<View> = if (view is ViewGroup) {
        listOf(view) + (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
    } else listOf(view)

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
