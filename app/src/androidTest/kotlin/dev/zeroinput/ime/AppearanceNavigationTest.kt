package dev.zeroinput.ime

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.settings.KeyboardAppearanceActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceNavigationTest {
    @Test fun reselectingCurrentSectionKeepsKeyboardFocusAndCheckedState() {
        ActivityScenario.launch(KeyboardAppearanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = descendants(activity.window.decorView).filterIsInstance<MaterialButton>().single {
                    it.text == activity.getString(R.string.appearance_section_style)
                }
                // Establish focus without changing the device-wide touch mode.
                button.isFocusableInTouchMode = true
                assertTrue("Fixture must acquire focus", button.requestFocus())
                button.performClick()
                assertTrue("Reselecting must retain focus", button.hasFocus())
                assertTrue(button.isChecked)
            }
        }
    }

    @Test fun recreationKeepsBackgroundSectionAndItsScrollWithoutChangingAppearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val original = settings.keyboardAppearance
        ActivityScenario.launch(KeyboardAppearanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<MaterialButton>().single {
                    it.text == activity.getString(dev.zeroinput.ime.ui.R.string.appearance_background)
                }.performClick()
            }
            instrumentation.waitForIdleSync()
            var offset = 0
            scenario.onActivity { activity ->
                val scroll = optionsScroll(activity)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                offset = scroll.scrollY
                assertTrue(offset > 0)
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(offset, optionsScroll(activity).scrollY)
                assertTrue(descendants(activity.window.decorView).filterIsInstance<MaterialButton>().single {
                    it.text == activity.getString(dev.zeroinput.ime.ui.R.string.appearance_background)
                }.isChecked)
                assertEquals(original, settings.keyboardAppearance)
            }
        }
    }

    private fun optionsScroll(activity: KeyboardAppearanceActivity) = descendants(activity.window.decorView)
        .filterIsInstance<ScrollView>().single { scroll ->
            descendants(scroll).any { it is dev.zeroinput.ime.settings.AppearanceOptionsView }
        }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
