package dev.zeroinput.ime.clipboard

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.security.AuthenticationGrant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SecurePasteReturnTest {
    @Test
    fun cancelTypingEditorSwitchSettingsAndDeletionRevokeTheReturnedPaste() {
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("clipboardTestPin") == "2468")
        for (case in listOf("cancel", "typing", "editor", "settings", "deletion",
            "queuedTyping", "queuedHide", "queuedScreenOff")) withFixture { activity, id ->
            val graph = (Device.context.applicationContext as ZeroInputApplication).graph
            val description = Device.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_open)
            Device.await { Device.roots().any { findDescription(it, description) != null } }
            checkNotNull(Device.roots().firstNotNullOfOrNull { findDescription(it, description) })
                .performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Device.click(Device.context.getString(R.string.secure_clipboard_ordinal, 1))
            Device.await("System credential input should be visible") {
                Device.roots().any { it.window?.isFocused == true && hasPasswordField(it) } ||
                    Device.shell("dumpsys window").lineSequence().any {
                        it.contains("mCurrentFocus=") && it.contains("BiometricPrompt}")
                    }
            }
            Device.instrumentation.uiAutomation.waitForIdle(100, 3_000)
            Device.shell("input text 2468")
            Device.shell("input keyevent KEYCODE_ENTER")
            val confirm = Device.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_confirm_paste)
            Device.await("Returned confirmation missing in $case case") { Device.findText(confirm) != null }
            val oldButton = checkNotNull(Device.findText(confirm))
            var other: InputFixtureActivity? = null
            val haptics = graph.settings.hapticFeedbackEnabled
            try {
                when (case) {
                    "cancel" -> Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_cancel_paste))
                    "typing" -> Device.onMain { activity.editor.append("public typed") }
                    "editor" -> {
                        // NEW_TASK alone can reuse the existing fixture task, so no new
                        // Activity reaches startActivitySync's creation monitor.
                        other = Device.instrumentation.startActivitySync(Intent(Device.context, InputFixtureActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as InputFixtureActivity
                        Device.showFixtureKeyboard(checkNotNull(other))
                    }
                    "settings" -> Device.onMain { graph.settings.hapticFeedbackEnabled = !haptics }
                    "deletion" -> { graph.secureClipboard.remove(id, grant()); Device.click(confirm) }
                    "queuedTyping", "queuedHide", "queuedScreenOff" -> revokeBlockedPaste(activity, confirm, case)
                }
                Device.await("A revoked operation must remove the paste confirmation") { Device.findText(confirm) == null }
                oldButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Device.instrumentation.waitForIdleSync()
                Device.onMain {
                    assertEquals(if (case in listOf("typing", "queuedTyping")) "public typed" else "", activity.editor.text.toString())
                    other?.let { assertEquals("", it.editor.text.toString()) }
                }
            } finally {
                Device.onMain { graph.settings.hapticFeedbackEnabled = haptics; other?.finish() }
            }
        }
    }

    private fun revokeBlockedPaste(activity: InputFixtureActivity, confirm: String, action: String) {
        val vault = (Device.context.applicationContext as ZeroInputApplication).graph.secureClipboard
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val blocker = worker.submit {
            try {
                // Hold the existing vault serialization boundary, without adding a fixture.
                vault.add("", "public unused fixture", grant(), isActive = {
                    locked.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    false
                })
            } catch (_: java.util.concurrent.CancellationException) { /* Expected; nothing was written. */ }
        }
        var screenTurnedOff = false
        try {
            assertTrue(locked.await(3, java.util.concurrent.TimeUnit.SECONDS))
            Device.click(confirm)
            Device.await { Device.findText(confirm) == null }
            when (action) {
                "queuedTyping" -> Device.onMain { activity.editor.append("public typed") }
                "queuedHide" -> Device.onMain {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.editor.windowToken, 0)
                }
                "queuedScreenOff" -> {
                    Device.shell("input keyevent KEYCODE_SLEEP")
                    screenTurnedOff = true
                    val power = activity.getSystemService(android.os.PowerManager::class.java)
                    Device.await("Fixture must turn the display off") { !power.isInteractive }
                }
            }
            Device.instrumentation.uiAutomation.waitForIdle(200, 3_000)
            release.countDown()
            blocker.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val deadline = android.os.SystemClock.elapsedRealtime() + 1_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                Device.onMain {
                    assertEquals(if (action == "queuedTyping") "public typed" else "", activity.editor.text.toString())
                }
                android.os.SystemClock.sleep(20)
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
            if (screenTurnedOff) {
                Device.shell("input keyevent KEYCODE_WAKEUP")
                Device.shell("wm dismiss-keyguard")
            }
        }
    }

    private fun withFixture(test: (InputFixtureActivity, String) -> Unit) {
        val graph = (Device.context.applicationContext as ZeroInputApplication).graph
        assumeTrue(graph.secureClipboard.count() == 0)
        val enabled = graph.settings.secureClipboardEnabled
        Device.onMain { graph.settings.secureClipboardEnabled = true }
        val item = graph.secureClipboard.add("", "public paste fixture", grant())
        val activity = Device.instrumentation.startActivitySync(Intent(Device.context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        try { Device.showFixtureKeyboard(activity); Device.instrumentation.waitForIdleSync(); test(activity, item.id) }
        finally {
            graph.secureClipboard.remove(item.id, grant())
            Device.onMain { graph.settings.secureClipboardEnabled = enabled; activity.finish() }
        }
    }

    private fun findDescription(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        if (node.contentDescription?.toString() == text) node else
            (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> findDescription(child, text) } }

    private fun hasPasswordField(node: AccessibilityNodeInfo): Boolean = (node.isPassword && node.isFocused) ||
        (0 until node.childCount).any { node.getChild(it)?.let(::hasPasswordField) == true }

    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()
}
