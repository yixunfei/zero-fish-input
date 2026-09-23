package dev.zeroinput.ime

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.settings.KeyboardThemeContext
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.InputEngineStatus
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.KeyboardTheme
import dev.zeroinput.ime.ui.ZeroInputView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class EnginePreparationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun preparationFitsSmallPortraitAndLandscapeInBothThemes() = onMain {
        val source = instrumentation.targetContext
        for ((width, height) in listOf(320 to 640, 540 to 280, 800 to 360)) {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(source.resources.configuration).apply {
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = width
                    screenHeightDp = height
                    uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
                }
                val context = KeyboardThemeContext.create(source.createConfigurationContext(config), KeyboardTheme.CLASSIC)
                val panel = ZeroInputView(context).apply {
                    setKeyboardHeight(KeyboardHeight.COMFORTABLE)
                    renderSession(InputSessionState(snapshot = EngineSnapshot("nihao", "ni hao", listOf(Candidate("1", "你好")))))
                    renderEngineStatus(InputEngineStatus.PREPARING)
                }
                panel.measure(View.MeasureSpec.makeMeasureSpec(dp(panel, width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(panel, height), View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val status = descendants(panel).filterIsInstance<TextView>().single {
                    it.text.toString() == context.getString(UiR.string.engine_preparing_basic)
                }
                assertTrue("Status must occupy visible space", visibleWithin(status, panel) && status.width > 0 && status.height > 0)
                assertEquals(0, status.layout.getEllipsisCount(0))
                val keyboard = descendants(panel).filterIsInstance<KeyboardPanel>().single()
                for (key in descendants(keyboard).filter { visibleWithin(it, panel) }) {
                    val bounds = Rect(0, 0, key.width, key.height)
                    panel.offsetDescendantRectToMyCoords(key, bounds)
                    assertTrue("Preparation must not push keys outside the window", bounds.bottom <= panel.height)
                }
                if (width > height) assertTrue(panel.height <= dp(panel, height - 48))
                panel.release()
            }
        }
    }

    @Test fun attachedProgressSurvivesToolsAndClearsOnReadyFailureAndRelease() {
        for (night in listOf(false, true)) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("night", night)) as KeyboardPreviewFixtureActivity
            try {
                onMain { activity.keyboard.renderEngineStatus(InputEngineStatus.PREPARING) }
                instrumentation.waitForIdleSync()
                onMain {
                    assertTrue(hasProgress(activity.keyboard))
                    descendants(activity.keyboard).first { it.contentDescription == it.context.getString(UiR.string.expression_smileys) }
                        .performClick()
                    assertTrue(hasProgress(activity.keyboard))
                    activity.keyboard.returnToKeyboard()
                }
                instrumentation.waitForIdleSync()
                capture("preparing-${if (night) "dark" else "light"}.png")
                onMain {
                    val panel = activity.keyboard
                    for (status in listOf(InputEngineStatus.READY, InputEngineStatus.FAILED, InputEngineStatus.HIDDEN)) {
                        panel.renderEngineStatus(status)
                        assertFalse(hasProgress(panel))
                        if (status == InputEngineStatus.FAILED) assertTrue(descendants(panel).any {
                            it.isShown && it.contentDescription == it.context.getString(UiR.string.retry_engine)
                        })
                    }
                    panel.renderEngineStatus(InputEngineStatus.PREPARING)
                    panel.release()
                    assertFalse(hasProgress(panel))
                }
            } finally { onMain { activity.finish() } }
        }
    }

    private fun hasProgress(view: View) = descendants(view).filterIsInstance<ProgressBar>().any { it.isShown && it.isIndeterminate }
    // Layout-only fixtures are not attached to a window, so View.isShown is always false.
    private fun visibleWithin(view: View, root: View): Boolean {
        var current = view
        while (true) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View ?: return false
        }
    }
    private fun capture(name: String) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "keyboard-fixtures").apply { mkdirs() }
            File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
