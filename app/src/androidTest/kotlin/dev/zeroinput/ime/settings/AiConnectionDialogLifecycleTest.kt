package dev.zeroinput.ime.settings

import android.content.Intent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ai.AiProbeState
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class AiConnectionDialogLifecycleTest {
    @Test fun replacedDialogDismissAndLateCallbacksCannotCancelTheNewTest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val windows = mutableListOf<AlertDialog>()
        val callbacks = mutableListOf<(AiProbeState) -> Unit>()
        val closed = mutableListOf<Int>()
        lateinit var owner: AiConnectionTestDialog
        try {
            instrumentation.runOnMainSync {
                owner = AiConnectionTestDialog(activity, start = { _, callback ->
                    val id = callbacks.size
                    callbacks += callback
                    callback(AiProbeState.Running)
                    AutoCloseable { closed += id }
                }, secure = { dialog, dismiss ->
                    windows += dialog
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.setOnDismissListener { dismiss() }
                    dialog.show()
                    dialog
                })
                val configuration = { AiConfiguration(enabled = true, networkAllowed = true) }
                owner.show(configuration)
                owner.show(configuration)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(listOf(0), closed)
                val retry = windows.last().getButton(AlertDialog.BUTTON_POSITIVE)
                assertFalse(retry.isEnabled)
                callbacks.first()(AiProbeState.Available)
                assertFalse(retry.isEnabled)
                callbacks.last()(AiProbeState.Available)
                assertTrue(retry.isEnabled)
                retry.performClick()
                assertFalse(retry.isEnabled)
                callbacks[1](AiProbeState.Cancelled)
                assertFalse(retry.isEnabled)
                owner.cancel()
                assertTrue(retry.isEnabled)
            }
        } finally {
            instrumentation.runOnMainSync {
                windows.forEach { it.dismiss() }
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }
}
