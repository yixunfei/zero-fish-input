package dev.zeroinput.ime

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.settings.SettingsCategory
import dev.zeroinput.ime.settings.SettingsScreenView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @Test fun tappingCategorySummaryOpensItsDestination() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val panel = screen(activity)
                val row = descendants(panel).single { it.tag == R.string.section_input }
                assertEquals(android.widget.Button::class.java.name, row.accessibilityClassName)
                assertTrue(row.isFocusable && row.isClickable)
                assertTrue(row.contentDescription.contains(activity.getString(R.string.settings_input_summary)))
                val summary = descendants(panel).filterIsInstance<android.widget.TextView>().single {
                    it.text == activity.getString(R.string.settings_input_summary)
                }
                val rect = android.graphics.Rect()
                summary.getDrawingRect(rect)
                panel.offsetDescendantRectToMyCoords(summary, rect)
                val time = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(time, time, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                    try { panel.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> assertEquals(SettingsCategory.INPUT, screen(activity).category) }
        }
    }

    @Test fun recreationRestoresCategoryAndScrollAndBackReturnsToHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> screen(activity).navigateTo(SettingsCategory.INPUT) }
            instrumentation.waitForIdleSync()
            var offset = 0
            scenario.onActivity { activity ->
                val scroll = descendants(screen(activity)).filterIsInstance<ScrollView>().single()
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                offset = scroll.scrollY
                assertTrue("Input options must provide a nonzero restoration fixture", offset > 0)
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(SettingsCategory.INPUT, screen(activity).category)
                assertEquals(offset, descendants(screen(activity)).filterIsInstance<ScrollView>().single().scrollY)
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(SettingsCategory.HOME, screen(activity).category)
                assertFalse(activity.isFinishing)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> screen(activity).navigateTo(SettingsCategory.INPUT) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(offset, descendants(screen(activity)).filterIsInstance<ScrollView>().single().scrollY)
            }
        }
    }

    private fun screen(activity: MainActivity) = descendants(activity.window.decorView)
        .filterIsInstance<SettingsScreenView>().single()

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
