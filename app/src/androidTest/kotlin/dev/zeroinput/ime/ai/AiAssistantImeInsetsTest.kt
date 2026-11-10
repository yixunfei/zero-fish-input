package dev.zeroinput.ime.ai

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ui.R as UiR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual configured IME with a fixed public draft; no provider or screen capture. */
@RunWith(AndroidJUnit4::class)
class AiAssistantImeInsetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun actualKeyboardLeavesTheDraftAndSendControlAboveItsInsets() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, AiAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as AiAssistantActivity
        try {
            await("Assistant window did not gain focus") { activity.hasWindowFocus() }
            instrumentation.runOnMainSync {
                val prompt = activity.findViewById<EditText>(UiR.id.ai_assistant_prompt)
                prompt.setText("Public keyboard inset fixture")
                prompt.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(prompt, InputMethodManager.SHOW_IMPLICIT)
            }
            await("The configured keyboard did not become visible") {
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            await("Input controls overlap the actual keyboard") {
                val root = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(root) ?: return@await false
                val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val availableBottom = root.height - keyboard
                listOf(UiR.id.ai_assistant_prompt, UiR.id.ai_assistant_send).all { id ->
                    val control = activity.findViewById<View>(id)
                    val bounds = Rect()
                    val visible = control.getGlobalVisibleRect(bounds)
                    visible && bounds.height() == control.height && bounds.top >= 0 && bounds.bottom <= availableBottom
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.finish()
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
}
