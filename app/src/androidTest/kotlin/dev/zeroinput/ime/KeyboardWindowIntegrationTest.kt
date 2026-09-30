package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardLayoutHost
import dev.zeroinput.ime.ui.KeyboardPlacementMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class KeyboardWindowIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun floatingWindowPassesOutsideTouchesAndAllModesRemainUsable() {
        val original = shell("settings get secure default_input_method").trim()
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val portrait = settings.keyboardPlacement(false)
        val landscape = settings.keyboardPlacement(true)
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            shell("ime enable $method"); shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            await { host() != null }
            onMain { requireNotNull(host()).selectMode(KeyboardPlacementMode.FLOATING) }
            await { host()?.let { it.height > it.inputBounds().height() + 100 } == true }
            var touches = 0
            val point = IntArray(2)
            onMain {
                val view = requireNotNull(host())
                val bounds = view.inputBounds()
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= view.width && bounds.bottom <= view.height)
                val insets = WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(activity.window.decorView.rootWindowInsets))
                assertTrue("Floating IME must not reserve its full-screen host", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom < bounds.height())
                activity.editor.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) touches++; false }
                activity.editor.getLocationOnScreen(point)
                point[0] += activity.editor.width / 2
                point[1] += activity.editor.height / 2
            }
            tap(point[0].toFloat(), point[1].toFloat())
            await { touches > 0 }
            for (mode in listOf(KeyboardPlacementMode.LEFT_HAND, KeyboardPlacementMode.RIGHT_HAND, KeyboardPlacementMode.DOCKED)) {
                onMain { requireNotNull(host()).selectMode(mode) }
                await { host()?.placement?.mode == mode }
                instrumentation.waitForIdleSync()
                onMain {
                    val view = requireNotNull(host())
                    assertTrue(view.height < activity.window.decorView.height)
                    assertTrue(view.inputBounds().right <= view.width)
                    val key = views(view).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "q" && it.isShown }
                    key.performClick()
                }
                await { activity.editor.text.isNotEmpty() }
                onMain { activity.editor.setText("") }
            }
        } finally {
            onMain { settings.saveKeyboardPlacement(false, portrait); settings.saveKeyboardPlacement(true, landscape) }
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    private fun host(): KeyboardLayoutHost? = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<KeyboardLayoutHost>().firstOrNull { it.isShown }

    @Test fun orientationSpecificPlacementSurvivesRotationAndRecreation() {
        val original = shell("settings get secure default_input_method").trim()
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val portrait = settings.keyboardPlacement(false)
        val landscape = settings.keyboardPlacement(true)
        val automation = instrumentation.uiAutomation
        var activity: InputFixtureActivity? = null
        try {
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            onMain {
                settings.saveKeyboardPlacement(false, portrait.copy(mode = KeyboardPlacementMode.FLOATING,
                    horizontalPosition = 0.2f, verticalPosition = 0.3f, floatingWidth = 0.8f))
                settings.saveKeyboardPlacement(true, landscape.copy(mode = KeyboardPlacementMode.RIGHT_HAND, oneHandWidth = 0.7f))
            }
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method"); shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            await { host()?.placement?.mode == KeyboardPlacementMode.FLOATING }
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            await { host()?.let { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                it.placement.mode == KeyboardPlacementMode.RIGHT_HAND } == true }
            onMain { assertEquals(0.7f, requireNotNull(host()).placement.oneHandWidth, 0.001f) }
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            await { host()?.let { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                it.placement.mode == KeyboardPlacementMode.FLOATING } == true }
            onMain {
                val placement = requireNotNull(host()).placement
                assertEquals(0.2f, placement.horizontalPosition, 0.001f)
                assertEquals(0.3f, placement.verticalPosition, 0.001f)
                val reopened = dev.zeroinput.ime.settings.SettingsRepository(instrumentation.targetContext)
                assertEquals(placement, reopened.keyboardPlacement(false))
                assertEquals(KeyboardPlacementMode.RIGHT_HAND, reopened.keyboardPlacement(true).mode)
            }
        } finally {
            automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            onMain { settings.saveKeyboardPlacement(false, portrait); settings.saveKeyboardPlacement(true, landscape) }
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 50 else 0, action, x, y, 0)
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Public IME window fixture did not reach expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
