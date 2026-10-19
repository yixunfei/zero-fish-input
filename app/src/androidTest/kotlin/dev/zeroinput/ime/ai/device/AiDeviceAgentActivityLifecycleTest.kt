package dev.zeroinput.ime.ai.device

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.widget.EditText
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ZeroInputApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiDeviceAgentActivityLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph

    @Test
    fun recreationKeepsTaskInactiveAndDoesNotRestorePrivateDraft() {
        val intent = Intent(context, AiDeviceAgentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val original = instrumentation.startActivitySync(intent) as AiDeviceAgentActivity
        var activityToFinish = original
        try {
            assertTrue(original.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val taskInput = findTaskInput(original)
            instrumentation.runOnMainSync { taskInput.setText("Private task draft") }
            assertEquals("Private task draft", taskInput.text.toString())

            instrumentation.runOnMainSync { original.recreate() }
            val recreated = awaitRecreatedActivity(original)
            activityToFinish = recreated

            assertNotSame(original, recreated)
            assertEquals("", findTaskInput(recreated).text.toString())
            assertTrue(recreated.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(!graph.deviceAgentCoordinator.hasActiveTask)
        } finally {
            instrumentation.runOnMainSync { activityToFinish.finish() }
        }
    }

    private fun findTaskInput(activity: AiDeviceAgentActivity): EditText {
        val inputs = mutableListOf<EditText>()
        collectInputs(activity.window.decorView, inputs)
        check(inputs.size >= 2) { "Expected target and task inputs" }
        return inputs[1]
    }

    private fun collectInputs(view: android.view.View, result: MutableList<EditText>) {
        if (view is EditText) result += view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) collectInputs(view.getChildAt(index), result)
        }
    }

    private fun awaitRecreatedActivity(previous: AiDeviceAgentActivity): AiDeviceAgentActivity {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val current = currentActivity()
            if (current != null && current !== previous) return current
            SystemClock.sleep(25L)
        }
        throw AssertionError("Recreated device task activity did not resume")
    }

    private fun currentActivity(): AiDeviceAgentActivity? {
        var current: AiDeviceAgentActivity? = null
        instrumentation.runOnMainSync {
            current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AiDeviceAgentActivity>()
                .firstOrNull()
        }
        return current
    }
}
