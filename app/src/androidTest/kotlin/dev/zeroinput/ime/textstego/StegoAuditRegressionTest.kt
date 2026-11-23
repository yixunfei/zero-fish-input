package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.button.MaterialButton
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoOperationGate
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test

class StegoAuditRegressionTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph

    @Test fun credentialIsDirectEnglishMaskedUntilExplicitRevealAndClearRequiresConfirmation() = withLiveWorkspace { workspace, view ->
        val panel = view.textStegoPanel
        lateinit var credential: StringBuilder
        Device.onMain {
            panel.onAction(StegoAction.CREDENTIAL)
            "nihao".forEach { view.dispatchKeyboardAction(KeyboardAction.Text(it.toString())) }
            assertFalse(texts(panel).any { it.isShown && it.text.toString() == "nihao" })
            assertTrue(texts(panel).any { it.text.toString() == Device.context.getString(R.string.stego_credential_input_help) })
            panel.onAction(StegoAction.REVEAL)
            assertTrue(texts(panel).any { it.isShown && it.text.toString() == "nihao" })
            panel.onAction(StegoAction.CLEAR_FIELD)
            credential = workspace.javaClass.getDeclaredField("credential").apply { isAccessible = true }.get(workspace) as StringBuilder
            assertEquals(5, credential.length)
            confirmDialog()
        }
        Device.instrumentation.waitForIdleSync()
        Device.onMain {
            assertEquals(0, credential.length)
            assertFalse(texts(panel).any { "nihao" in it.text })
            view.dispatchKeyboardAction(KeyboardAction.Text("x"))
            panel.onAction(StegoAction.REVEAL)
            assertTrue(texts(panel).any { it.isShown && it.text.toString() == "x" })
            view.dispatchKeyboardAction(KeyboardAction.Text("y"))
            assertFalse(texts(panel).any { it.isShown && it.text.toString() == "xy" })
        }
    }

    @Test fun typedChineseHitsByteLimitBeforeCryptoAndNewInputClearsTheNotice() = withLiveWorkspace { _, view ->
        val panel = view.textStegoPanel
        Device.onMain {
            panel.onAction(StegoAction.SECRET)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText("汉".repeat(682) + "ab"))
            view.dispatchKeyboardAction(KeyboardAction.LiteralText("汉"))
            assertTrue(texts(panel).any { it.text.toString() == Device.context.getString(R.string.stego_length_limit) })
            panel.onAction(StegoAction.PREPARE)
            assertTrue(texts(panel).any { it.text.toString() == Device.context.getString(R.string.stego_length_limit) })
            assertFalse(texts(panel).any { it.text.toString() == Device.context.getString(R.string.stego_busy) })
            panel.onAction(StegoAction.CLEAR_FIELD); confirmDialog()
        }
        Device.instrumentation.waitForIdleSync()
        Device.onMain {
            view.dispatchKeyboardAction(KeyboardAction.LiteralText("short public fixture"))
            assertFalse(texts(panel).any { it.text.toString() == Device.context.getString(R.string.stego_length_limit) })
        }
    }

    @Test fun credentialRejectsUtf8OverflowBeforeCrypto() = withWorkspace { _, view ->
        val panel = view.textStegoPanel
        panel.onAction(StegoAction.CREDENTIAL)
        val exactLimit = "汉".repeat(341) + "a"
        view.dispatchKeyboardAction(KeyboardAction.LiteralText(exactLimit))
        view.dispatchKeyboardAction(KeyboardAction.LiteralText("b"))
        assertTrue(texts(panel).any { it.text.toString() == view.context.getString(R.string.stego_wording_credential_limit) })
        panel.onAction(StegoAction.REVEAL)
        assertTrue(texts(view).any { it.isShown && it.text.toString() == exactLimit })
        assertFalse(texts(view).any { it.text.toString() == exactLimit + "b" })
    }

    @Test fun disabledVaultHasNoPasteEntryAndPublicCoverAloneIsAccessible() {
        val enabled = graph.settings.secureClipboardEnabled
        val defaults = graph.settings.stegoDefaults
        try {
            Device.onMain {
                graph.settings.secureClipboardEnabled = false
                graph.settings.stegoDefaults = defaults.copy(vaultPaste = true, systemPaste = false)
            }
            withWorkspace { _, view ->
                val panel = view.textStegoPanel
                assertFalse(texts(panel).filterIsInstance<MaterialButton>().any {
                    it.isShown && it.text.toString() == view.context.getString(dev.zeroinput.ime.ui.R.string.stego_paste_field)
                })
                for ((hint, accessible) in listOf(dev.zeroinput.ime.ui.R.string.stego_cover_hint to true,
                    dev.zeroinput.ime.ui.R.string.stego_secret_hint to false,
                    dev.zeroinput.ime.ui.R.string.stego_credential_hint to false)) {
                    val body = texts(panel).single { it.hint?.toString() == view.context.getString(hint) }
                    assertEquals(accessible, body.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES)
                    assertFalse(body.isLongClickable)
                }
            }
        } finally { Device.onMain { graph.settings.secureClipboardEnabled = enabled; graph.settings.stegoDefaults = defaults } }
    }

    @Test fun idleWarningAppearsBeforeExpiryAndFieldInteractionRenewsTheBudget() = withWorkspace { workspace, view ->
        val gate = workspace.javaClass.getDeclaredField("gate").apply { isAccessible = true }.get(workspace) as StegoOperationGate
        gate.javaClass.getDeclaredField("deadline").apply { isAccessible = true }
            .setLong(gate, SystemClock.elapsedRealtime() + 29_000)
        (workspace.javaClass.getDeclaredField("expire").apply { isAccessible = true }.get(workspace) as Runnable).run()
        assertTrue(texts(view.textStegoPanel).any { it.text.toString().contains("29") })
        view.textStegoPanel.onAction(StegoAction.SECRET)
        assertTrue(gate.remainingMillis() > 119_000)
        assertFalse(texts(view.textStegoPanel).any { it.text.toString().contains("29") })
    }

    @Test fun privateHostRenewsItsOwnDeadlineOnContinuedEditing() {
        var token = ""
        Device.onMain { token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, "public fixture".toCharArray())) }
        val activity = Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token)) as StegoSourceActivity
        try { Device.onMain {
            val deadline = activity.javaClass.getDeclaredField("deadline").apply { isAccessible = true }
            deadline.setLong(activity, SystemClock.elapsedRealtime() + 5000)
            val keyboard = nodes(activity.window.decorView).filterIsInstance<ZeroInputView>().single()
            keyboard.textStegoPanel.onAction(StegoAction.COVER)
            keyboard.dispatchKeyboardAction(KeyboardAction.LiteralText("x"))
            assertTrue(deadline.getLong(activity) - SystemClock.elapsedRealtime() > 119_000)
        } } finally { Device.onMain { activity.finish(); graph.stegoHandoff.close() } }
    }

    private fun withWorkspace(action: (StegoWorkspace, ZeroInputView) -> Unit) = withLiveWorkspace { workspace, view ->
        Device.onMain { action(workspace, view) }
    }
    private fun withLiveWorkspace(action: (StegoWorkspace, ZeroInputView) -> Unit) {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            lateinit var view: ZeroInputView
            lateinit var workspace: StegoWorkspace
            scenario.onActivity { activity ->
                view = activity.keyboard
                workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { true }, { null }, {}, {}, {})
                view.onKeyboardAction = workspace::type
                view.openTextStego(); workspace.open(InputLanguage.CHINESE, ChineseInputOptions())
            }
            try { action(workspace, view) } finally { scenario.onActivity { workspace.close(); view.release() } }
        }
    }
    private fun confirmDialog() = WindowInspector.getGlobalWindowViews().single {
        it.findViewById<View>(android.R.id.button1) != null
    }.findViewById<View>(android.R.id.button1).performClick()
    private fun texts(view: View) = nodes(view).filterIsInstance<TextView>()
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
