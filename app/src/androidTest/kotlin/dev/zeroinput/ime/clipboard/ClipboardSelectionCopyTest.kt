package dev.zeroinput.ime.clipboard

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.AndroidSystemClipboard
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.ime.ui.R as UiR
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ClipboardSelectionCopyTest {
    @Test
    fun keyboardSelectionSavesAndPastesOnlyAfterAuthenticationAndExplicitConfirmation() {
        val pin = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("clipboardTestPin")
        assumeTrue("Requires a disposable emulator with an explicitly configured test PIN", pin == "2468")
        val graph = (Device.context.applicationContext as ZeroInputApplication).graph
        assumeTrue("Use an empty fixture vault", graph.secureClipboard.count() == 0)
        val enabled = graph.settings.secureClipboardEnabled
        val clipboard = AndroidSystemClipboard(Device.context)
        val activity = Device.instrumentation.startActivitySync(Intent(Device.context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        var createdId: String? = null
        try {
            Device.showFixtureKeyboard(activity)
            val baseline = clipboard.timestamp()
            Device.onMain { activity.editor.setText("public selection fixture"); activity.editor.selectAll() }
            Device.instrumentation.waitForIdleSync()
            openPanel(UiR.string.keyboard_tools)
            openPanel(UiR.string.secure_clipboard_copy_selection)
            val action = Device.context.getString(if (enabled) UiR.string.secure_clipboard_copy_authenticate
                else UiR.string.secure_clipboard_copy_enable)
            Device.await { Device.findText(action) != null }
            Device.onMain {
                assertTrue("Review must leave the source window focused", activity.hasWindowFocus())
                assertEquals("public selection fixture", activity.editor.text.toString())
            }
            assertEquals(0, graph.secureClipboard.count())
            Device.click(action)
            authenticateFixture()
            val save = Device.context.getString(UiR.string.secure_clipboard_copy_save)
            Device.await("Authentication must return to an explicit save in the keyboard") { Device.findText(save) != null }
            Device.onMain { assertTrue(activity.hasWindowFocus()) }
            assertEquals("Authentication alone cannot save", 0, graph.secureClipboard.count())
            Device.click(save)
            Device.await { graph.secureClipboard.count() == 1 }
            Device.await { graph.settings.secureClipboardEnabled }
            val entry = graph.secureClipboard.summaries().single()
            createdId = entry.id
            assertEquals("public selection fixture", graph.secureClipboard.read(entry.id, grant()))
            assertEquals(baseline, clipboard.timestamp())
            Device.showFixtureKeyboard(activity)
            Device.onMain { activity.editor.setText("") }
            Device.instrumentation.waitForIdleSync()
            val itemLabel = Device.context.getString(dev.zeroinput.ime.R.string.secure_clipboard_ordinal, 1)
            if (Device.findText(Device.context.getString(UiR.string.secure_clipboard_copy_selection)) == null) openPanel()
            Device.click(itemLabel)
            authenticateFixture()
            val confirm = Device.context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_confirm_paste)
            Device.await("Returning from authentication must show a fresh paste confirmation") { Device.findText(confirm) != null }
            Device.onMain { assertEquals("", activity.editor.text.toString()) }
            Device.click(confirm)
            Device.await("Explicit confirmation must paste into the current fixture") {
                var pasted = false
                Device.onMain { pasted = activity.editor.text.toString() == "public selection fixture" }
                pasted
            }
            assertEquals(baseline, clipboard.timestamp())
        } finally {
            createdId?.let { graph.secureClipboard.remove(it, grant()) }
            Device.onMain { graph.settings.secureClipboardEnabled = enabled; activity.finish() }
        }
    }

    @Test
    fun movingSelectionOrCancellingDismissesTheReviewWithoutSaving() {
        val graph = (Device.context.applicationContext as ZeroInputApplication).graph
        val before = graph.secureClipboard.count()
        val activity = Device.instrumentation.startActivitySync(Intent(Device.context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        try {
            Device.showFixtureKeyboard(activity)
            Device.onMain { activity.editor.setText("public selection fixture"); activity.editor.selectAll() }
            Device.instrumentation.waitForIdleSync()
            openPanel()
            val copy = Device.context.getString(UiR.string.secure_clipboard_copy_selection)
            val cancel = Device.context.getString(UiR.string.secure_clipboard_copy_cancel)
            Device.click(copy)
            Device.await { Device.findText(cancel) != null }
            Device.onMain { activity.editor.setSelection(0) }
            Device.await("Changing the editor selection must revoke copy review") { Device.findText(cancel) == null }
            Device.onMain { activity.editor.selectAll() }
            Device.instrumentation.waitForIdleSync()
            Device.click(copy)
            Device.click(cancel)
            Device.await { Device.findText(cancel) == null }
            assertEquals(before, graph.secureClipboard.count())
        } finally {
            Device.onMain { activity.finish() }
        }
    }

    private fun openPanel(label: Int = UiR.string.secure_clipboard_open) {
        val description = Device.context.getString(label)
        Device.await { Device.roots().any { root -> findDescription(root, description) != null } }
        val button = checkNotNull(Device.roots().firstNotNullOfOrNull { findDescription(it, description) })
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) return node
        return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> findDescription(child, description) } }
    }

    private fun authenticateFixture() {
        Device.await("System device-credential input should be visible") {
            Device.roots().any { it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") &&
                it.window?.isFocused == true && hasPasswordField(it) } ||
                Device.shell("dumpsys window").lineSequence().any {
                    it.contains("mCurrentFocus=") && it.contains("BiometricPrompt}")
                }
        }
        Device.instrumentation.uiAutomation.waitForIdle(100, 3_000)
        Device.shell("input text 2468")
        Device.shell("input keyevent KEYCODE_ENTER")
    }

    private fun hasPasswordField(node: AccessibilityNodeInfo): Boolean = (node.isPassword && node.isFocused) ||
        (0 until node.childCount).any { node.getChild(it)?.let(::hasPasswordField) == true }

    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()
}
