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
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("Floating IME must not reserve its full-screen host", imeBottom < view.height)
                activity.editor.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) touches++; false }
                val hostOrigin = IntArray(2).also(view::getLocationOnScreen)
                bounds.offset(hostOrigin[0], hostOrigin[1])
                val editorBounds = android.graphics.Rect()
                assertTrue(activity.editor.getGlobalVisibleRect(editorBounds))
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                editorBounds.left += bars.left + 24
                editorBounds.top += bars.top + 24
                editorBounds.right -= bars.right + 24
                editorBounds.bottom -= bars.bottom + 24
                val outside = listOf(
                    editorBounds.centerX() to editorBounds.top + editorBounds.height() / 4,
                    editorBounds.left to editorBounds.top,
                    editorBounds.right to editorBounds.top,
                    editorBounds.left to editorBounds.bottom,
                ).firstOrNull { (x, y) -> !bounds.contains(x, y) }
                assertNotNull("The fixture needs an editor point outside the floating keyboard", outside)
                point[0] = requireNotNull(outside).first
                point[1] = outside.second
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
                    assertEquals("Docked and one-hand panels must not reserve the navigation bar twice",
                        view.height, view.inputBounds().bottom)
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
        instrumentation.waitForIdleSync()
        shell("input tap ${x.toInt()} ${y.toInt()}")
        instrumentation.waitForIdleSync()
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
