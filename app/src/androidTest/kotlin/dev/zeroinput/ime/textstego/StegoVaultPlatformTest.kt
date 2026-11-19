package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.security.message.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoVaultPlatformTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph

    @Test fun coverPasteAuthenticatesOnceAndReturnsOnlyToTheOriginalNewWorkspace() {
        requirePublicPin()
        assertEquals(0, graph.secureClipboard.count())
        val enabled = graph.settings.secureClipboardEnabled
        val cover = "public pasted cover"
        val id = graph.secureClipboard.add("", cover, grant()).id
        val editor = dev.zeroinput.ime.clipboard.PasteEditorIdentity("public.fixture", 7, 1)
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val detached = MessageCredential(MessageCredentialMode.RANDOM_KEY, key.toCharArray()).use {
            StegoCarrierCodec.ciphertext(MessageEnvelope().seal(cover.toCharArray(), "public secret".toCharArray(), it, kdf = MessageKdf.NONE))
        }
        var activity: StegoSourceActivity? = null
        try {
            var token = ""
            Device.onMain {
                graph.settings.secureClipboardEnabled = true
                token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.VAULT, editor = editor,
                    target = StegoField.COVER, carrier = detached.toCharArray()))
            }
            activity = Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token)) as StegoSourceActivity
            val source = activity
            clickStegoVaultEntry(1)
            authenticate()
            Device.await { Device.findText(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_read)) != null }
            Device.onMain { assertNull(graph.stegoHandoff.takePaste(editor)) }
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_read))
            await { onMain { source.isFinishing } }
            Device.onMain {
                val accepted = requireNotNull(graph.stegoHandoff.takePaste(editor))
                try {
                    assertEquals(StegoField.COVER, accepted.target)
                    assertTrue(cover == String(requireNotNull(accepted.text)))
                    assertTrue(detached == String(requireNotNull(accepted.carrier)))
                    assertNull(graph.stegoHandoff.takePaste(editor))
                } finally { accepted.close() }
            }
        } finally {
            Device.onMain { activity?.finish(); graph.stegoHandoff.close() }
            graph.secureClipboard.remove(id, grant())
            Device.onMain { graph.settings.secureClipboardEnabled = enabled }
        }
    }

    @Test fun vaultReadAndSaveEachRequireSystemAuthenticationAndAnotherForegroundConfirmation() {
        requirePublicPin()
        assertEquals("Public fixture requires an empty vault", 0, graph.secureClipboard.count())
        val enabled = graph.settings.secureClipboardEnabled
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val carrier = MessageCredential(MessageCredentialMode.RANDOM_KEY, key.toCharArray()).use {
            StegoCarrierCodec.encode("public cover", MessageEnvelope().seal("public cover".toCharArray(),
                "public saved secret".toCharArray(), it, kdf = MessageKdf.NONE), StegoTransport.VISIBLE_BASE64)
        }
        val original = graph.secureClipboard.add("", carrier, grant()).id
        var activity: StegoSourceActivity? = null
        try {
            var token = ""
            Device.onMain {
                graph.settings.secureClipboardEnabled = true
                token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.VAULT))
            }
            activity = Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token)) as StegoSourceActivity
            val source = activity
            Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_vault))
            clickStegoVaultEntry(1)
            authenticate()
            Device.await { onMain { source.isFinishing } || Device.findText(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_read)) != null }
            Device.onMain { assertFalse("Authentication must return to the protected source", source.isFinishing) }
            Device.onMain { assertFalse(nodes(source.window.decorView).any { it is ZeroInputView }) }
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_read))
            await { onMain { nodes(source.window.decorView).filterIsInstance<ZeroInputView>().any { it.isTextStegoOpen } } }
            Device.onMain {
                val keyboard = nodes(source.window.decorView).filterIsInstance<ZeroInputView>().single()
                keyboard.dispatchKeyboardAction(KeyboardAction.LiteralText(key))
                keyboard.textStegoPanel.onAction(StegoAction.PREPARE)
            }
            await { onMain { nodes(source.window.decorView).filterIsInstance<TextView>().any {
                it.isShown && it.text.toString() == Device.context.getString(dev.zeroinput.ime.R.string.stego_result)
            } } }
            Device.onMain {
                val keyboard = nodes(source.window.decorView).filterIsInstance<ZeroInputView>().single()
                keyboard.textStegoPanel.onAction(StegoAction.SAVE)
            }
            Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_save))
            authenticate()
            await {
                onMain { nodes(source.window.decorView).filterIsInstance<TextView>().any {
                    it.isShown && it.text.toString() == Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save)
                } }
            }
            assertEquals("Authentication alone must not write", 1, graph.secureClipboard.count())
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save))
            await { graph.secureClipboard.count() == 2 }
            val saved = graph.secureClipboard.summaries().single { it.id != original }
            assertEquals("public saved secret", graph.secureClipboard.read(saved.id, grant()))
        } finally {
            Device.onMain { activity?.finish(); graph.stegoHandoff.close() }
            graph.secureClipboard.summaries().forEach { graph.secureClipboard.remove(it.id, grant()) }
            Device.onMain { graph.settings.secureClipboardEnabled = enabled }
        }
    }

    @Test fun canceledAuthenticationOffersRetryAndLeavingDiscardsPendingSaveWithoutWriting() {
        requirePublicPin()
        val count = graph.secureClipboard.count()
        val activity = startSave()
        try {
            Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_save))
            awaitPrompt()
            Device.shell("input keyevent KEYCODE_BACK")
            Device.await { Device.findText(Device.context.getString(dev.zeroinput.ime.R.string.stego_retry_authentication)) != null }
            assertEquals(count, graph.secureClipboard.count())
            Device.onMain {
                assertFalse(activity.isFinishing)
                assertFalse(nodes(activity.window.decorView).any { it is ZeroInputView })
            }
            Device.shell("input keyevent KEYCODE_BACK")
            Device.await { onMain { activity.isFinishing } }
        } finally { Device.onMain { activity.finish(); graph.stegoHandoff.close() } }
    }

    @Test fun saveDraftLifetimeDoesNotExpireDuringSeparateAuthenticationAndConfirmationWindows() {
        requirePublicPin()
        assertEquals("Public fixture requires an empty vault", 0, graph.secureClipboard.count())
        val enabled = graph.settings.secureClipboardEnabled
        val activity = startSave()
        try {
            // Reach the old draft boundary without a two-minute test sleep.
            Device.onMain {
                activity.javaClass.getDeclaredField("deadline").apply { isAccessible = true }
                    .setLong(activity, SystemClock.elapsedRealtime() + 5000)
            }
            Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_save))
            authenticate()
            Device.await { Device.findText(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save)) != null }
            SystemClock.sleep(16_000)
            assertEquals(0, graph.secureClipboard.count())
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save))
            await { graph.secureClipboard.count() == 1 }
            assertEquals("public pending secret", graph.secureClipboard.read(graph.secureClipboard.summaries().single().id, grant()))
        } finally {
            Device.onMain { activity.finish(); graph.stegoHandoff.close() }
            graph.secureClipboard.summaries().forEach { graph.secureClipboard.remove(it.id, grant()) }
            Device.onMain { graph.settings.secureClipboardEnabled = enabled }
        }
    }

    @Test fun settingsChangeRevokesAuthenticatedSaveBeforeConfirmation() {
        requirePublicPin()
        val count = graph.secureClipboard.count()
        val old = graph.settings.incognitoMode
        val activity = startSave()
        try {
            Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_save))
            authenticate()
            Device.await { Device.findText(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save)) != null }
            Device.onMain { graph.settings.incognitoMode = !old }
            Device.await { onMain { activity.isFinishing } }
            assertEquals(count, graph.secureClipboard.count())
        } finally { Device.onMain { activity.finish(); graph.stegoHandoff.close(); graph.settings.incognitoMode = old } }
    }

    @Test fun vaultDeletionRevokesAuthenticatedSaveAndCannotResurrectDeletedData() {
        requirePublicPin()
        assertEquals("Public fixture requires an empty vault", 0, graph.secureClipboard.count())
        val fixture = graph.secureClipboard.add("", "public fixture to remove", grant()).id
        val activity = startSave()
        try {
            Device.click(Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_save))
            authenticate()
            Device.await { Device.findText(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save)) != null }
            assertTrue(graph.secureClipboard.remove(fixture, grant()))
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_save))
            Device.instrumentation.waitForIdleSync()
            assertEquals(0, graph.secureClipboard.count())
            Device.onMain { assertFalse(nodes(activity.window.decorView).any { it is ZeroInputView }) }
        } finally {
            Device.onMain { activity.finish(); graph.stegoHandoff.close() }
            if (graph.secureClipboard.summaries().any { it.id == fixture }) graph.secureClipboard.remove(fixture, grant())
        }
    }

    private fun startSave(): StegoSourceActivity {
        var token = ""
        Device.onMain { token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.SAVE, "public pending secret".toCharArray())) }
        return Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token)) as StegoSourceActivity
    }

    private fun requirePublicPin() {
        assumeTrue("Requires the disposable public-fixture emulator PIN",
            InstrumentationRegistry.getArguments().getString("stegoTestPin") == "2468")
        // A preceding cancellation can still be dismissing its system window.
        Device.instrumentation.uiAutomation.waitForIdle(500, 3000)
    }

    internal fun authenticate() {
        awaitPrompt()
        Device.instrumentation.uiAutomation.waitForIdle(100, 3000)
        for (label in listOf("Use PIN", "USE PIN", "Use screen lock", "使用 PIN 码")) {
            if (Device.findText(label) != null) { Device.click(label); Device.instrumentation.uiAutomation.waitForIdle(100, 3000); break }
        }
        // The API 37 credential prompt owns a numeric keypad rather than an EditText.
        // Search its virtual children directly; text-search IPC misses these controls.
        for (digit in listOf("2", "4", "6", "8")) {
            Device.await("Public system PIN digit $digit must be available") { systemDigit(digit) != null }
            var node = requireNotNull(systemDigit(digit))
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        // Confirm on the system keypad itself. A hardware Enter can activate a
        // separately focused Cancel control after accessibility digit clicks.
        Device.await("Public system PIN confirmation must be available") { systemConfirmation() != null }
        var confirmation = requireNotNull(systemConfirmation())
        while (!confirmation.isClickable && confirmation.parent != null) confirmation = confirmation.parent
        assertTrue(confirmation.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        Device.await("Public system PIN prompt must finish dismissing") { systemConfirmation() == null }
        Device.instrumentation.uiAutomation.waitForIdle(500, 3000)
    }
    private fun awaitPrompt() {
        Device.await("Public system authentication must be focused") {
            Device.shell("dumpsys window").lineSequence().any { it.contains("mCurrentFocus=") && it.contains("BiometricPrompt}") } ||
            Device.roots().any { root -> root.window?.isFocused == true &&
                    root.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") && password(root) }
        }
    }
    private fun systemDigit(digit: String): android.view.accessibility.AccessibilityNodeInfo? = Device.roots()
        .filter { it.window?.isFocused == true && it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") }
        .firstNotNullOfOrNull { findNode(it, digit) }
    private fun systemConfirmation(): android.view.accessibility.AccessibilityNodeInfo? = Device.roots()
        .filter { it.window?.isFocused == true && it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") }
        .firstNotNullOfOrNull(::findConfirmation)
    private fun findConfirmation(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? =
        if (node.isVisibleToUser && node.viewIdResourceName?.endsWith(":id/key_enter") == true) node else
            (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::findConfirmation) }
    private fun findNode(node: android.view.accessibility.AccessibilityNodeInfo, digit: String): android.view.accessibility.AccessibilityNodeInfo? =
        if (node.text?.toString() == digit && node.isVisibleToUser) node else
            (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> findNode(child, digit) } }
    private fun password(node: android.view.accessibility.AccessibilityNodeInfo): Boolean = node.isPassword && node.isFocused ||
        (0 until node.childCount).any { node.getChild(it)?.let(::password) == true }
    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null; Device.onMain { value = runCatching(block) }; return requireNotNull(value).getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Public vault fixture did not settle", condition())
    }
}
