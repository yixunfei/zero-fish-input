package dev.zeroinput.ime.clipboardguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ZeroInputService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemClipboardPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = ClipboardDeviceTestSupport

    @Test
    fun confirmationPageClearsOnlyAfterTheForegroundButtonIsPressed() = withConfirmationFixture { runtime, port, _ ->
        device.click(instrumentation.targetContext.getString(R.string.clipboard_guard_confirm_action))
        await { runtime.state.status == ClipboardGuardStatus.CLEARED }
        assertNull(port.timestamp())
    }

    @Test
    fun cancellingConfirmationLeavesTheCurrentItemUntouched() = withConfirmationFixture { runtime, port, activity ->
        val timestamp = checkNotNull(runtime.state.ticket).timestamp
        device.click(instrumentation.targetContext.getString(R.string.cancel))
        awaitFinished(activity)
        assertTrue("Cancellation must preserve the public fixture", timestamp == port.timestamp())
        assertEquals(ClipboardGuardStatus.CHANGED, runtime.state.status)
    }

    @Test
    fun disablingMonitoringDismissesConfirmationWithoutClearing() = withConfirmationFixture { runtime, port, activity ->
        val timestamp = checkNotNull(runtime.state.ticket).timestamp
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        instrumentation.runOnMainSync { graph.clipboardGuardPreferences.options = ClipboardGuardOptions() }
        awaitFinished(activity)
        await { runtime.state.status == ClipboardGuardStatus.OFF }
        assertTrue("Revoked monitoring must preserve the public fixture", timestamp == port.timestamp())
        assertNull(runtime.state.ticket)
    }

    @Test
    fun defaultImeAutomaticModeClearsANewPublicFixture() = withObservedFixture(ClipboardClearMode.AUTOMATIC) { runtime, port ->
        await { runtime.state.status == ClipboardGuardStatus.CLEARED }
        assertNull(port.timestamp())
        assertNull(runtime.state.ticket)
    }

    @Test
    fun openingAutomaticProtectionWithAnotherKeyboardClearsTheExistingCurrentItem() {
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val original = graph.clipboardGuardPreferences.options
        val originalIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val other = device.shell("ime list -s").lineSequence().map(String::trim)
            .firstOrNull { it.contains('/') && it != originalIme }
        assumeTrue(!original.listening && other != null)
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        val port = AndroidSystemClipboard(context)
        var page: android.app.Activity? = null
        var timestamp: Long? = null
        try {
            device.showFixtureKeyboard(activity)
            assumeTrue(port.timestamp() == null)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "public guard fixture"))
            timestamp = port.timestamp()
            assertNotNull(timestamp)
            device.shell("ime set $other")
            device.onMain { graph.clipboardGuardPreferences.options = ClipboardGuardOptions(
                listening = true, clearMode = ClipboardClearMode.AUTOMATIC) }
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.NOT_DEFAULT }
            page = instrumentation.startActivitySync(Intent(context, ClipboardGuardSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.CLEARED }
            assertNull(port.timestamp())
            assertEquals(other, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        } finally {
            device.onMain { graph.clipboardGuardPreferences.options = original }
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.OFF }
            if (timestamp != null && port.timestamp() == timestamp) port.clear()
            device.shell("ime set $originalIme")
            device.onMain { page?.finish(); activity.finish() }
            device.flushGuardPreferences()
        }
    }


    @Test
    fun copyingInADifferentApplicationClearsTheCurrentItemAndCanShowAnOverlay() {
        val support = ClipboardDeviceTestSupport
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val original = graph.clipboardGuardPreferences.options
        assumeTrue(!original.listening)
        assumeTrue("Select ZeroInput before running cross-application clipboard tests", ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty(),
        ) == ComponentName(context, ZeroInputService::class.java))
        val port = AndroidSystemClipboard(context)
        var fixtureTimestamp: Long? = null
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        try {
            support.showFixtureKeyboard(activity)
            assumeTrue(port.timestamp() == null)
            support.withOverlayPermission(true) {
                support.onMain { graph.clipboardGuardPreferences.options = ClipboardGuardOptions(
                    listening = true, clearMode = ClipboardClearMode.AUTOMATIC, overlayReminder = true, overlaySeconds = 10,
                ) }
                await { graph.clipboardGuard.state.status == ClipboardGuardStatus.WAITING }
                support.onMain { activity.finish() }
                support.startSource()
                support.click("Copy public fixture")
                fixtureTimestamp = port.timestamp()
                await { graph.clipboardGuard.state.status == ClipboardGuardStatus.CLEARED }
                assertNull(port.timestamp())
                await { support.overlayWindows().size == 1 }
                val first = graph.clipboardGuard.state.eventId
                support.click("Copy public fixture")
                fixtureTimestamp = port.timestamp()
                await { graph.clipboardGuard.state.eventId != first && graph.clipboardGuard.state.status == ClipboardGuardStatus.CLEARED }
                assertNull(port.timestamp())
                await { support.overlayWindows().size == 1 }
            }
        } finally {
            support.onMain { graph.clipboardGuardPreferences.options = original; activity.finish() }
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.OFF }
            if (fixtureTimestamp != null && port.timestamp() == fixtureTimestamp) port.clear()
            support.findText("Close public fixture")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            support.flushGuardPreferences()
        }
    }

    @Test
    fun keyboardPrivateCopyPreservesTheCurrentItemWithoutAutomaticMode() {
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val original = graph.clipboardGuardPreferences.options
        assumeTrue("Run with the real guard disabled", !original.listening)
        assumeTrue("Select ZeroInput as the test device's default IME", ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty(),
        ) == ComponentName(context, ZeroInputService::class.java))
        val port = AndroidSystemClipboard(context)
        var fixtureTimestamp: Long? = null
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        try {
            device.showFixtureKeyboard(activity)
            val manager = context.getSystemService(ClipboardManager::class.java)
            assumeTrue("Never replace an existing clipboard", manager.primaryClipDescription == null)
            // Establish the current item before monitoring so confirm mode only
            // records a baseline and cannot act on it without the user.
            manager.setPrimaryClip(ClipData.newPlainText("", "public guard fixture"))
            fixtureTimestamp = port.timestamp()
            assertNotNull(fixtureTimestamp)
            device.onMain { graph.clipboardGuardPreferences.options = ClipboardGuardOptions(
                listening = true, clearMode = ClipboardClearMode.CONFIRM) }
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.WAITING }
            device.onMain { activity.editor.setText("public selection fixture"); activity.editor.selectAll() }
            instrumentation.waitForIdleSync()
            openSecureClipboardPanel()
            val copyLabel = context.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_copy_selection)
            device.await("Copy-selection control must be enabled") {
                var node = device.findText(copyLabel)
                while (node != null && !node.isClickable) node = node.parent
                node?.isEnabled == true
            }
            device.click(copyLabel)
            val review = listOf(context.getString(R.string.clipboard_import_authenticate),
                context.getString(R.string.enable_secure_clipboard))
            device.await("The private import review page must appear") {
                review.any { device.findText(it)?.window?.isFocused == true }
            }
            assertEquals("A private copy must not touch the system clipboard without automatic mode",
                fixtureTimestamp, port.timestamp())
        } finally {
            device.shell("input keyevent KEYCODE_BACK")
            device.onMain { graph.clipboardGuardPreferences.options = original }
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.OFF }
            if (fixtureTimestamp != null && port.timestamp() == fixtureTimestamp) port.clear()
            device.onMain { activity.finish() }
            ClipboardDeviceTestSupport.flushGuardPreferences()
        }
    }

    @Test
    fun publicFixtureTriggersMetadataCallbackAndCanBeClearedWithoutReadingItsBody() {
        val context = instrumentation.targetContext
        assumeTrue("Run with the real guard disabled", !ClipboardGuardPreferences(context).options.listening)
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        val port = AndroidSystemClipboard(context)
        var registration: AutoCloseable? = null
        var fixtureTimestamp: Long? = null
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var focused = false
            while (!focused && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                if (!focused) SystemClock.sleep(10)
            }
            assertTrue(focused)
            val manager = context.getSystemService(ClipboardManager::class.java)
            assumeTrue("Never replace an existing clipboard", manager.primaryClipDescription == null)
            val changed = CountDownLatch(1)
            registration = port.listen { changed.countDown() }
            manager.setPrimaryClip(ClipData.newPlainText("", "public guard fixture"))
            fixtureTimestamp = port.timestamp()
            assertNotNull(fixtureTimestamp)
            assertTrue(changed.await(5, TimeUnit.SECONDS))
            assumeTrue("Do not clear a replacement clipboard", fixtureTimestamp == port.timestamp())
            port.clear()
            assertNull(port.timestamp())
        } finally {
            registration?.close()
            if (fixtureTimestamp != null && port.timestamp() == fixtureTimestamp) port.clear()
            instrumentation.runOnMainSync { activity.finish() }
            ClipboardDeviceTestSupport.flushGuardPreferences()
        }
    }

    private fun openSecureClipboardPanel() {
        val description = instrumentation.targetContext.getString(dev.zeroinput.ime.ui.R.string.secure_clipboard_open)
        device.await("Secure clipboard panel control must be visible") {
            device.roots().any { findDescription(it, description) != null }
        }
        val button = checkNotNull(device.roots().firstNotNullOfOrNull { findDescription(it, description) })
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) return node
        return (0 until node.childCount).firstNotNullOfOrNull {
            node.getChild(it)?.let { child -> findDescription(child, description) }
        }
    }

    private fun withConfirmationFixture(test: (ClipboardGuardRuntime, SystemClipboardPort, ClipboardClearActivity) -> Unit) {
        withObservedFixture(ClipboardClearMode.CONFIRM) { runtime, port ->
            await { runtime.state.ticket != null }
            val ticket = checkNotNull(runtime.state.ticket)
            val context = instrumentation.targetContext
            val activity = instrumentation.startActivitySync(Intent(context, ClipboardClearActivity::class.java)
                .putExtra(ClipboardClearActivity.EXTRA_TICKET, ticket.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ClipboardClearActivity
            try {
                val label = context.getString(R.string.clipboard_guard_confirm_action)
                device.await {
                    device.findText(label)?.window?.isFocused == true
                }
                assertTrue("Opening confirmation must preserve the public fixture", ticket.timestamp == port.timestamp())
                test(runtime, port, activity)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun awaitFinished(activity: ClipboardClearActivity) = await {
        var finished = false
        instrumentation.runOnMainSync { finished = activity.isFinishing }
        finished
    }

    private fun withObservedFixture(mode: ClipboardClearMode, test: (ClipboardGuardRuntime, SystemClipboardPort) -> Unit) {
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val original = graph.clipboardGuardPreferences.options
        assumeTrue("Run with the real guard disabled", !original.listening)
        assumeTrue("Select ZeroInput as the test device's default IME", ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty(),
        ) == ComponentName(context, ZeroInputService::class.java))
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        val port = AndroidSystemClipboard(context)
        var fixtureTimestamp: Long? = null
        try {
            device.showFixtureKeyboard(activity)
            val manager = context.getSystemService(ClipboardManager::class.java)
            assumeTrue("Never replace an existing clipboard", manager.primaryClipDescription == null)
            instrumentation.runOnMainSync {
                graph.clipboardGuardPreferences.options = ClipboardGuardOptions(listening = true, clearMode = mode)
            }
            await(message = { "Monitoring did not start: ${graph.clipboardGuard.state.status}" }) {
                graph.clipboardGuard.state.status == ClipboardGuardStatus.WAITING
            }
            manager.setPrimaryClip(ClipData.newPlainText("", "public guard fixture"))
            fixtureTimestamp = port.timestamp()
            test(graph.clipboardGuard, port)
        } finally {
            instrumentation.runOnMainSync { graph.clipboardGuardPreferences.options = original }
            await { graph.clipboardGuard.state.status == ClipboardGuardStatus.OFF }
            if (fixtureTimestamp != null && port.timestamp() == fixtureTimestamp) port.clear()
            instrumentation.runOnMainSync { activity.finish() }
            ClipboardDeviceTestSupport.flushGuardPreferences()
        }
    }

    private fun await(message: () -> String = { "Platform flow did not settle" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue(message(), condition())
    }
}
