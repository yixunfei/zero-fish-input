package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Before
import org.junit.After
import dev.zeroinput.ime.R

@RunWith(AndroidJUnit4::class)
class StegoPastePanelTest {
    private var enabled = false
    @Before fun enableVaultMenuFixture() = Device.onMain {
        val settings = (Device.context.applicationContext as ZeroInputApplication).graph.settings
        enabled = settings.secureClipboardEnabled; settings.secureClipboardEnabled = true
    }
    @After fun restoreVaultMenuFixture() = Device.onMain {
        (Device.context.applicationContext as ZeroInputApplication).graph.settings.secureClipboardEnabled = enabled
    }
    @Test fun presetAndCustomMasterLengthsGenerateMaskedCanonicalCredentials() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            lateinit var workspace: StegoWorkspace
            lateinit var view: ZeroInputView
            val notices = mutableListOf<Int>()
            scenario.onActivity { activity ->
                view = activity.keyboard
                val graph = (Device.context.applicationContext as ZeroInputApplication).graph
                workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { true }, { null },
                    { notices += it }, {}, {})
                view.onKeyboardAction = workspace::type
                view.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                view.textStegoPanel.onAction(StegoAction.CREDENTIAL_MODE)
            }
            Device.click(Device.context.getString(R.string.stego_random_key))
            try {
                for (bits in listOf(512, 1024, 4096, 264)) {
                    scenario.onActivity {
                        if (bits == 264) {
                            view.textStegoPanel.onAction(StegoAction.KEY_BITS)
                            view.dispatchKeyboardAction(KeyboardAction.LiteralText(bits.toString()))
                        } else view.textStegoPanel.onKeyBits(bits)
                        view.textStegoPanel.onAction(StegoAction.GENERATE_KEY)
                    }
                    Device.await {
                        var ready = false
                        Device.onMain { ready = nodes(view.textStegoPanel).filterIsInstance<TextView>().none {
                            it.isShown && it.text.toString() == Device.context.getString(R.string.stego_busy)
                        } }
                        ready
                    }
                    scenario.onActivity {
                        val raw = workspace.javaClass.getDeclaredField("credential").apply { isAccessible = true }.get(workspace) as StringBuilder
                        assertEquals(bits / 8, java.util.Base64.getUrlDecoder().decode(raw.toString()).size)
                        assertFalse(nodes(view.textStegoPanel).filterIsInstance<TextView>().any { raw.toString() in it.text })
                        if (bits == 4096) {
                            view.textStegoPanel.onAction(StegoAction.REVEAL)
                            val body = nodes(view.textStegoPanel).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == raw.toString() }
                            assertEquals(Int.MAX_VALUE, body.maxLines)
                            view.textStegoPanel.onAction(StegoAction.REVEAL)
                        }
                    }
                }
                scenario.onActivity {
                    view.textStegoPanel.onAction(StegoAction.KEY_BITS)
                    view.dispatchKeyboardAction(KeyboardAction.LiteralText("257"))
                    view.textStegoPanel.onAction(StegoAction.GENERATE_KEY)
                    assertTrue(R.string.stego_invalid_bits in notices)
                }
            } finally { scenario.onActivity { workspace.close(); view.release() } }
        }
    }
    @Test fun longPressRoutesOnlyPublicFieldsAndOldCallbacksCannotLaunchSources() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var workspace: StegoWorkspace? = null
            lateinit var panel: StegoPanelView
            var sources = emptyList<StegoAction>()
            var stale: (StegoField) -> Unit = {}
            scenario.onActivity { activity ->
                val graph = (Device.context.applicationContext as ZeroInputApplication).graph
                val view = activity.keyboard
                panel = view.textStegoPanel
                workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { true }, { null }, {},
                    { sources = sources + it }, {})
                view.openTextStego(); workspace?.open(InputLanguage.ENGLISH, ChineseInputOptions())
                assertTrue(workspace?.pasteText(StegoField.COVER, "public replacement") == true)
                assertTrue(workspace?.pasteText(StegoField.SECRET, "public secret") == true)
                assertFalse(nodes(panel).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "public secret" })
                val body = nodes(panel).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == "public replacement" }
                assertTrue(body.performLongClick())
                stale = panel.onPasteRequested
            }
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_paste_vault))
            scenario.onActivity { activity ->
                assertEquals(listOf(StegoAction.VAULT_COVER), sources)
                workspace?.close(); workspace?.open(InputLanguage.ENGLISH, ChineseInputOptions())
                stale(StegoField.COVER)
                panel.onPasteRequested(StegoField.SECRET)
                panel.onPasteRequested(StegoField.CREDENTIAL)
                assertEquals(1, sources.size)
                panel.onPasteRequested(StegoField.COVER)
                assertTrue(workspace?.pasteText(StegoField.COVER, "public edit while the menu is open") == true)
            }
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_paste_vault))
            scenario.onActivity { activity ->
                assertEquals("A source choice for an older draft must be revoked", 1, sources.size)
                workspace?.close(); activity.keyboard.release()
            }
        }
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
