package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboard.ClipboardImportActivity
import dev.zeroinput.ime.clipboard.ClipboardImportView
import dev.zeroinput.ime.clipboard.PasteEditorIdentity
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.message.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoSourcePlatformTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph
    private val randomKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test fun rejectedLaunchDiscardsOnlyItsOwnHandoffAndWipesPendingSave() {
        Device.onMain {
            val chars = "public aborted save".toCharArray()
            val token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.SAVE, chars))
            graph.stegoHandoff.discard(token)
            assertTrue(chars.all { it == '\u0000' })
            assertNull(graph.stegoHandoff.take(token))
            val newer = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.FILE))
            graph.stegoHandoff.discard(token)
            val retained = graph.stegoHandoff.take(newer)
            assertNotNull(retained)
            retained?.close()
        }
    }

    @Test fun pendingCarrierCompositionIsValidatedBeforeStartingCrypto() {
        val carrier = MessageCredential(MessageCredentialMode.RANDOM_KEY, randomKey.toCharArray()).use {
            StegoCarrierCodec.encode("public cover", MessageEnvelope().seal("public cover".toCharArray(),
                "public fixture".toCharArray(), it, kdf = MessageKdf.NONE), StegoTransport.VISIBLE_BASE64)
        }
        val token = onMain { graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, carrier.toCharArray())) }
        val activity = Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token)) as StegoSourceActivity
        try {
            Device.onMain {
                val keyboard = nodes(activity.window.decorView).filterIsInstance<ZeroInputView>().single()
                keyboard.textStegoPanel.onAction(StegoAction.CARRIER)
                keyboard.dispatchKeyboardAction(KeyboardAction.Text("x"))
                keyboard.textStegoPanel.onAction(StegoAction.CREDENTIAL)
                keyboard.dispatchKeyboardAction(KeyboardAction.LiteralText(randomKey))
                keyboard.textStegoPanel.onAction(StegoAction.PREPARE)
                assertTrue(nodes(keyboard).filterIsInstance<TextView>().any { it.isShown &&
                    it.text.toString() == Device.context.getString(dev.zeroinput.ime.R.string.stego_failed) })
                assertFalse(nodes(keyboard).filterIsInstance<TextView>().any { it.isShown &&
                    it.text.toString() == Device.context.getString(dev.zeroinput.ime.R.string.stego_busy) })
            }
        } finally { Device.onMain { activity.finish(); graph.stegoHandoff.close() } }
    }

    @Test fun shareAndProcessTextRequireChoiceDecryptLocallyAndRecreationDiscardsAllState() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT)) {
            val carrier = MessageCredential(MessageCredentialMode.RANDOM_KEY, randomKey.toCharArray()).use {
                StegoCarrierCodec.encode("public cover", MessageEnvelope().seal("public cover".toCharArray(),
                    "public secret fixture".toCharArray(), it, kdf = MessageKdf.NONE), StegoTransport.VISIBLE_BASE64)
            }
            val intent = Intent(Device.context, ClipboardImportActivity::class.java).setAction(action).setType("text/plain")
                .putExtra(if (action == Intent.ACTION_SEND) Intent.EXTRA_TEXT else Intent.EXTRA_PROCESS_TEXT, carrier)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val importer = Device.instrumentation.startActivitySync(intent) as ClipboardImportActivity
            var source: StegoSourceActivity? = null
            try {
                Device.onMain {
                    val root = importer.window.decorView
                    assertFalse(nodes(root).filterIsInstance<TextView>().any { it.isShown && it.text.contains("public secret fixture") })
                    val view = nodes(root).filterIsInstance<ClipboardImportView>().single()
                    view.onAiAction() // Carrier permission is denied at the model boundary as well.
                    view.onStegoAction()
                }
                await { source = resumedSource(); source != null }
                val activity = requireNotNull(source)
                Device.onMain {
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertNull(activity.intent.extras)
                    val keyboard = nodes(activity.window.decorView).filterIsInstance<ZeroInputView>().single()
                    assertTrue(keyboard.isTextStegoOpen)
                    assertFalse(nodes(keyboard).any { it is android.widget.EditText })
                    keyboard.textStegoPanel.onAction(StegoAction.OPTIONS)
                    val fixed = android.view.inspector.WindowInspector.getGlobalWindowViews().single { window ->
                        window.findViewById<View>(android.R.id.button1) != null &&
                        nodes(window).filterIsInstance<TextView>().any { it.text.toString() ==
                            Device.context.getString(dev.zeroinput.ime.R.string.stego_decrypt_options_fixed) }
                    }
                    assertFalse(nodes(fixed).filterIsInstance<TextView>().any { it.text.toString() ==
                        Device.context.getString(dev.zeroinput.ime.R.string.stego_credential_mode) })
                    requireNotNull(fixed.findViewById<View>(android.R.id.button1)).performClick()
                    keyboard.dispatchKeyboardAction(KeyboardAction.LiteralText(randomKey))
                    keyboard.textStegoPanel.onAction(StegoAction.PREPARE)
                }
                await { onMain { nodes(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.isShown && it.text.toString() == Device.context.getString(dev.zeroinput.ime.R.string.stego_result) } } }
                Device.onMain {
                    val keyboard = nodes(activity.window.decorView).filterIsInstance<ZeroInputView>().single()
                    assertFalse(nodes(keyboard).filterIsInstance<TextView>().any { it.isShown && it.text.contains("public secret fixture") })
                    keyboard.textStegoPanel.onAction(StegoAction.REVEAL)
                    assertTrue(nodes(keyboard).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "public secret fixture" })
                    assertFalse(nodes(keyboard).filterIsInstance<TextView>().any { it.isShown && it.text.toString() ==
                        Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_insert) })
                    activity.recreate()
                }
                await { resumedSource() == null }
            } finally {
                Device.onMain { source?.finish(); importer.finish() }
                Device.onMain { graph.stegoHandoff.close() }
            }
        }
    }

    @Test fun handoffsAreOneUseAndSettingsDeletionAndDifferentEditorsRevokeFactors() {
        val text = "public carrier".toCharArray()
        var token = ""
        Device.onMain { token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, text)) }
        val accepted = onMain { graph.stegoHandoff.take(token) }
        assertNotNull(accepted)
        assertNull(onMain { graph.stegoHandoff.take(token) })
        accepted?.close()
        assertTrue(text.all { it == '\u0000' })
        val old = graph.settings.incognitoMode
        try {
            Device.onMain {
                val pending = "public pending".toCharArray()
                token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, pending))
                graph.settings.incognitoMode = !old
                assertNull(graph.stegoHandoff.take(token))
                assertTrue(pending.all { it == '\u0000' })
                val editor = PasteEditorIdentity("dev.zeroinput.public-fixture", 1, 1)
                graph.stegoHandoff.offerFile(ByteArray(32), editor)
                assertNull(graph.stegoHandoff.takeFile(editor.copy(fieldId = 2)))
                assertNull(graph.stegoHandoff.takeFile(editor))
            }
        } finally { Device.onMain { graph.settings.incognitoMode = old; graph.stegoHandoff.close() } }
    }

    @Test fun privateSourceRejectsMissingAndReplayedTokens() {
        var token = ""
        Device.onMain {
            token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, "public fixture".toCharArray()))
            graph.stegoHandoff.take(token)?.close()
        }
        for (candidate in listOf(null, token)) {
            val activity = Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, candidate)) as StegoSourceActivity
            try {
                Device.onMain { assertTrue(activity.isFinishing); assertFalse(nodes(activity.window.decorView).any { it is ZeroInputView }) }
            } finally { Device.onMain { activity.finish() } }
        }
    }

    private fun resumedSource(): StegoSourceActivity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<StegoSourceActivity>().firstOrNull()
    }
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        Device.onMain { value = runCatching(block) }
        return requireNotNull(value).getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Public source fixture did not settle", condition())
    }
}
