package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

/** Exercises the real IME window, not a detached panel or a mocked flag policy. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class AiScreenCaptureWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aiDraftAndActionsRemainCapturableUntilIncognitoIsEnabled() {
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val originalMethod = shell("settings get secure default_input_method").trim()
        val originalLearning = settings.learningEnabled
        val originalIncognito = settings.incognitoMode
        var fixture: InputFixtureActivity? = null
        try {
            onMain { settings.learningEnabled = true; settings.incognitoMode = false }
            val method = "${instrumentation.targetContext.packageName}/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method")
            shell("ime set $method")
            fixture = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            val activity = fixture
            onMain {
                activity.editor.imeOptions = 0
                activity.getSystemService(InputMethodManager::class.java).restartInput(activity.editor)
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.editor, 0)
            }
            await { panel() != null }
            onMain { assertFalse(isSecure()) }
            onMain {
                val keyboard = requireNotNull(panel())
                views(keyboard).first { it.isShown && it.contentDescription == it.context.getString(UiR.string.ai_open) }
                    .performClick()
            }
            await { panel()?.isAiOpen == true }
            onMain {
                assertFalse("Opening AI must not block screenshots or recordings", isSecure())
                requireNotNull(panel()).onAiDraftChanged(KeyboardAction.Text("public demo"))
                assertFalse("Editing the AI draft must remain capturable", isSecure())
                views(requireNotNull(panel())).filterIsInstance<android.widget.TextView>().first {
                    it.isShown && it.text == it.context.getString(UiR.string.ai_read)
                }.performClick()
                assertFalse(requireNotNull(panel()).isAiEditing)
                assertFalse("AI actions must remain capturable", isSecure())
            }
            assertAiRegionCaptured()
            onMain {
                requireNotNull(panel()).returnToKeyboard()
                assertFalse(isSecure())
                settings.incognitoMode = true
            }
            await { panel() != null && isSecure() }
            onMain { settings.incognitoMode = false }
            await { panel() != null && !isSecure() }
        } finally {
            fixture?.let { onMain { it.finish() } }
            onMain { settings.learningEnabled = originalLearning; settings.incognitoMode = originalIncognito }
            if (originalMethod.isNotBlank() && originalMethod != "null") shell("ime set $originalMethod")
        }
    }

    private fun panel() = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }

    private fun isSecure(): Boolean {
        val root = requireNotNull(panel()).rootView
        val params = root.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_INPUT_METHOD, params.type)
        return params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun assertAiRegionCaptured() {
        instrumentation.waitForIdleSync()
        val bounds = android.graphics.Rect()
        onMain {
            val view = requireNotNull(panel())
            val origin = IntArray(2).also(view::getLocationOnScreen)
            bounds.set(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
        }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var visiblePixels = 0
            for (y in bounds.top until minOf(bounds.bottom, screenshot.height) step 8) {
                for (x in bounds.left until minOf(bounds.right, screenshot.width) step 8) {
                    val color = screenshot.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 20 || android.graphics.Color.green(color) > 20 ||
                        android.graphics.Color.blue(color) > 20) visiblePixels++
                }
            }
            assertTrue("AI content must appear in a system capture instead of a black secure surface", visiblePixels > 100)
        } finally { screenshot.recycle() }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Public AI capture fixture did not reach expected window state")
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
