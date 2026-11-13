package dev.zeroinput.ime

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardActivationStatusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun settingsReflectsSystemKeyboardChangesWhileItRemainsOpen() {
        val ownMethod = ComponentName(context, ZeroInputService::class.java).flattenToShortString()
        val original = checkNotNull(currentMethod())
        assertNotEquals("Start the test with the system keyboard selected", ownMethod, original)
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == ownMethod }

        try {
            shell("ime enable $ownMethod")
            assertTrue(shell("ime list -s").lineSequence().any { it.trim() == ownMethod })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertStatus(scenario, R.string.setting_not_selected)

                shell("ime set $ownMethod")
                assertEquals(ownMethod, currentMethod())
                assertStatus(scenario, R.string.setting_current)

                shell("ime set $original")
                assertEquals(original, currentMethod())
                assertStatus(scenario, R.string.setting_not_selected)
            }
        } finally {
            shell("ime set $original")
            if (!wasEnabled) shell("ime disable $ownMethod")
        }
    }

    private fun currentMethod(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    private fun assertStatus(scenario: ActivityScenario<MainActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var displayed = false
            scenario.onActivity { activity ->
                displayed = containsVisibleText(activity.window.decorView, activity.getString(expected))
            }
            if (displayed) return
            SystemClock.sleep(50)
        }
        fail("Open settings did not show ${context.getString(expected)}")
    }

    private fun containsVisibleText(view: View, expected: String): Boolean {
        if (!view.isShown) return false
        if (view is TextView && view.text.toString() == expected) return true
        return view is ViewGroup && (0 until view.childCount).any { containsVisibleText(view.getChildAt(it), expected) }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
