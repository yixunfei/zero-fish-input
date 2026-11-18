package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.message.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class StegoKeyboardIntegrationTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph

    @Test fun isolatedDraftPreviewsThenCommitsOnlyAuthenticatedCarrierOnce() = withEditor { activity ->
        val cover = "public cover 🌏"
        val secret = "public secret fixture"
        val passphrase = "public fixture passphrase"
        var bindingRevision = 0L
        Device.onMain {
            val view = keyboard()
            view.onTextStegoRequested()
            assertTrue(view.isTextStegoOpen)
            assertTrue(WindowInspector.getGlobalWindowViews().any {
                it.layoutParams.let { p -> p is WindowManager.LayoutParams && p.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
            })
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(cover))
            view.textStegoPanel.onAction(StegoAction.SECRET)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(secret))
            assertFalse(labels(view).any { secret in it })
            assertEquals("", activity.editor.text.toString())
            view.textStegoPanel.onAction(StegoAction.CREDENTIAL)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(passphrase))
            assertFalse(labels(view).any { passphrase in it })
            view.textStegoPanel.onAction(StegoAction.PREPARE)
            bindingRevision = interactionRevision(view)
        }
        await(30_000) { onMain { labels(keyboard()).any { "UTF-16" in it } } }
        Device.onMain {
            assertEquals("", activity.editor.text.toString())
            assertFalse(labels(keyboard()).any { secret in it })
            assertEquals("External interaction revoked the prepared binding", bindingRevision, interactionRevision(keyboard()))
            keyboard().textStegoPanel.onAction(StegoAction.CONFIRM)
            keyboard().textStegoPanel.onAction(StegoAction.CONFIRM)
        }
        await { onMain { activity.editor.text.isNotEmpty() } }
        val encoded = onMain { activity.editor.text.toString() }
        val carrier = StegoCarrierCodec.decode(encoded)
        assertEquals(cover, carrier.cover)
        MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase.toCharArray()).use { key ->
            val opened = MessageEnvelope().open(carrier.cover.toCharArray(), carrier.envelope, key)
            assertArrayEquals(secret.toCharArray(), opened)
            opened.fill('\u0000')
        }
        Device.onMain {
            assertEquals(encoded, activity.editor.text.toString())
            assertTrue(activity.editorActions.isEmpty())
            assertFalse(keyboard().isTextStegoOpen)
        }
    }

    @Test fun selectionDecryptIsMaskedLocalAndSessionChangesRevokePreparedOutput() = withEditor { activity ->
        val secret = "public decrypted fixture"
        val passphrase = "public fixture passphrase"
        val envelope = MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase.toCharArray()).use {
            MessageEnvelope().seal("public cover".toCharArray(), secret.toCharArray(), it)
        }
        val encoded = StegoCarrierCodec.encode("public cover", envelope, dev.zeroinput.ime.core.stego.StegoTransport.DENSE_UNICODE)
        Device.onMain { activity.editor.setText(encoded); activity.editor.selectAll() }
        Device.instrumentation.waitForIdleSync()
        Device.onMain {
            keyboard().onTextStegoRequested()
            assertEquals("Opening the mode must preserve the selected carrier", encoded, activity.editor.text.toString())
            keyboard().textStegoPanel.onAction(StegoAction.DECRYPT)
            keyboard().textStegoPanel.onAction(StegoAction.SELECTION)
        }
        // Selection extraction is asynchronous; wait for the credential field to become selected.
        await { onMain { views(keyboard().textStegoPanel).filterIsInstance<TextView>().any {
            it.isSelected && it.text == it.context.getString(dev.zeroinput.ime.ui.R.string.stego_credential)
        } } }
        Device.onMain {
            keyboard().dispatchKeyboardAction(KeyboardAction.LiteralText(passphrase))
            keyboard().textStegoPanel.onAction(StegoAction.PREPARE)
        }
        val resultLabel = Device.context.getString(dev.zeroinput.ime.R.string.stego_result)
        val failureLabels = listOf(dev.zeroinput.ime.R.string.stego_failed,
            dev.zeroinput.ime.R.string.stego_memory_unavailable, dev.zeroinput.ime.R.string.stego_worker_busy)
        await(30_000) { onMain { labels(keyboard()).any { it == resultLabel || it in failureLabels.map(Device.context::getString) } } }
        Device.onMain {
            val failure = failureLabels.indexOfFirst { Device.context.getString(it) in labels(keyboard()) }
            assertTrue("Decrypt failed with public error category $failure", resultLabel in labels(keyboard()))
        }
        Device.onMain {
            assertEquals(encoded, activity.editor.text.toString())
            assertFalse(labels(keyboard()).any { secret in it })
            keyboard().textStegoPanel.onAction(StegoAction.REVEAL)
            assertTrue(labels(keyboard()).any { secret in it })
            activity.editor.setSelection(0)
        }
        Device.instrumentation.waitForIdleSync()
        await { onMain { !keyboard().isTextStegoOpen } }
        Device.onMain { assertEquals(encoded, activity.editor.text.toString()) }
    }

    @Test fun passwordAndNoPersonalizationEditorsCannotOpenSteganography() {
        for ((type, flags) in listOf((InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 0,
            InputType.TYPE_CLASS_TEXT to android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
            withEditor(type, flags) { _ ->
                Device.onMain { keyboard().onTextStegoRequested(); assertFalse(keyboard().isTextStegoOpen) }
            }
        }
    }

    private fun withEditor(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                           flags: Int = 0, action: (InputFixtureActivity) -> Unit) {
        val original = Device.shell("settings get secure default_input_method").trim()
        val incognito = graph.settings.incognitoMode
        val privateDefault = graph.settings.privateDraftEnabled
        assertFalse("Public isolated fixture requires private-default off", privateDefault)
        var activity: InputFixtureActivity? = null
        try {
            Device.onMain { graph.settings.incognitoMode = false }
            Device.shell("ime enable ${Device.context.packageName}/dev.zeroinput.ime.ZeroInputService")
            Device.shell("ime set ${Device.context.packageName}/dev.zeroinput.ime.ZeroInputService")
            activity = Device.instrumentation.startActivitySync(Intent(Device.context, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ordinary_fixture", true)
                .putExtra("input_type", type).putExtra("ime_options", flags)) as InputFixtureActivity
            Device.showFixtureKeyboard(activity)
            action(activity)
        } finally {
            activity?.let { Device.onMain { it.finish() } }
            Device.onMain { graph.settings.incognitoMode = incognito }
            if (original.isNotBlank() && original != "null") Device.shell("ime set $original")
        }
    }

    private fun keyboard() = requireNotNull(WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown })
    private fun interactionRevision(view: ZeroInputView): Long {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is dev.zeroinput.ime.ZeroInputService) context = context.baseContext
        val service = context as dev.zeroinput.ime.ZeroInputService
        return dev.zeroinput.ime.ZeroInputService::class.java.getDeclaredField("interactionSequence").apply { isAccessible = true }.getLong(service)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = views(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        Device.onMain { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Public steganography fixture did not settle", condition())
    }
}
