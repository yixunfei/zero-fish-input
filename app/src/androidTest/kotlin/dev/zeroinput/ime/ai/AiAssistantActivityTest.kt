package dev.zeroinput.ime.ai

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.zeroinput.ime.ZeroInputApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class AiAssistantActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph

    @Test fun independentChatOpensWithoutImeActivationAndRecreationDoesNotRestoreTheDraft() {
        val originalConfig = graph.aiConfigurationSnapshot()
        val original = instrumentation.startActivitySync(Intent(context, AiAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as AiAssistantActivity
        var toFinish = original
        try {
            assertTrue(original.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val prompt = original.findViewById<EditText>(UiR.id.ai_assistant_prompt)
            instrumentation.runOnMainSync { prompt.setText("Public independent draft"); original.recreate() }
            val recreated = awaitActivity(original)
            toFinish = recreated
            assertEquals("", recreated.findViewById<EditText>(UiR.id.ai_assistant_prompt).text.toString())
            assertTrue(recreated.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(!graph.deviceAgentCoordinator.hasActiveTask)
            // Avoid assertions that could print a configured credential on failure.
            if (originalConfig != null) assertTrue(originalConfig == graph.aiConfigurationSnapshot())
        } finally { instrumentation.runOnMainSync { toFinish.finish() } }
    }

    @Test fun bothLauncherEntriesAreResolvableButTheContentScreensArePrivate() {
        val manager = context.packageManager
        val entries = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName), 0).map { it.activityInfo.name }.toSet()
        assertTrue(entries.contains("dev.zeroinput.ime.settings.MainActivity"))
        assertTrue(entries.contains(AiAssistantLauncherActivity::class.java.name))
        assertEquals("dev.zeroinput.ime.settings.MainActivity", manager.getLaunchIntentForPackage(context.packageName)?.component?.className)
        val assistant = manager.getActivityInfo(android.content.ComponentName(context, AiAssistantActivity::class.java), 0)
        val device = manager.getActivityInfo(android.content.ComponentName(context,
            dev.zeroinput.ime.ai.device.AiDeviceAgentActivity::class.java), 0)
        assertFalse(assistant.exported)
        assertFalse(device.exported)
    }

    private fun awaitActivity(previous: AiAssistantActivity): AiAssistantActivity {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var current: AiAssistantActivity? = null
            instrumentation.runOnMainSync {
                current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AiAssistantActivity>().firstOrNull { it !== previous }
            }
            current?.let { return it }
            SystemClock.sleep(25)
        }
        throw AssertionError("Private assistant did not resume after recreation")
    }
}
