package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.settings.*
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.message.*
import org.junit.Assert.*
import org.junit.Test

class StegoDefaultsPlatformTest {
    private val settings get() = (Device.context.applicationContext as ZeroInputApplication).graph.settings

    @Test fun newWorkspaceUsesPersistedWidthsOrderAndBitsAndKeepsCiphersFolded() = withDefaults { original ->
        val expected = original.copy(masterBits = 1024,
            stages = listOf(MessageStage(MessageCipher.SM4), MessageStage(MessageCipher.AES, 192)))
        Device.onMain { settings.stegoDefaults = expected }
        withWorkspace { panel, workspace ->
            val checks = nodes(panel).filterIsInstance<CheckBox>()
            assertTrue(checks.none { it.isShown })
            assertEquals(listOf("1. SM4-GCM", "2. AES-GCM"), checks.filter { it.isChecked }.map { it.text.toString() }.sorted())
            val keyBits = workspace.javaClass.getDeclaredField("keyBits").apply { isAccessible = true }
            assertEquals("1024", keyBits.get(workspace))
            panel.onSelectAlgorithm(MessageCipher.SM4.id, false)
            workspace.close(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
            assertEquals(expected, settings.stegoDefaults)
            assertEquals(2, checks.count { it.isChecked })
            assertTrue(checks.none { it.isShown })
        }
    }

    @Test fun secretLongPressOffersOnlyEnabledSourcesAndNeverCredentialPaste() = withDefaults { original ->
        Device.onMain { settings.stegoDefaults = original.copy(vaultPaste = true, systemPaste = false) }
        withWorkspace { panel, workspace ->
            workspace.pasteText(StegoField.SECRET, "public fixture")
            val body = nodes(panel).filterIsInstance<TextView>().single {
                it.isShown && it.hint?.toString() == it.context.getString(dev.zeroinput.ime.ui.R.string.stego_secret_hint)
            }
            assertTrue(body.performLongClick())
            val menu = dialog()
            val choices = nodes(menu).filterIsInstance<android.widget.ListView>().single().adapter
            val sources = (0 until choices.count).map { choices.getItem(it).toString() }
            assertTrue(Device.context.getString(R.string.stego_paste_vault) in sources)
            assertFalse(Device.context.getString(R.string.stego_paste_system) in sources)
            (workspace.javaClass.getDeclaredField("dialogs").apply { isAccessible = true }.get(workspace) as StegoOptionsDialog).close()
            assertFalse(nodes(panel).filterIsInstance<TextView>().single {
                it.isShown && it.hint?.toString() == it.context.getString(dev.zeroinput.ime.ui.R.string.stego_credential_hint)
            }.isLongClickable)
            settings.stegoDefaults = original.copy(vaultPaste = false, systemPaste = false)
            workspace.type(KeyboardAction.LiteralText("!"))
            assertFalse(body.isLongClickable)
            panel.onPasteRequested(StegoField.SECRET)
            assertFalse(WindowInspector.getGlobalWindowViews().any { nodes(it).filterIsInstance<TextView>().any { text ->
                text.text.toString() == Device.context.getString(R.string.stego_paste_title)
            } })
            assertFalse(workspace.pasteText(StegoField.CREDENTIAL, "denied"))
            assertFalse(workspace.pasteText(StegoField.SECRET, "中".repeat(683)))
        }
    }

    @Test fun settingsPageSavesCascadeAndSwitchesAndDefaultLengthThroughRealControls() = withDefaults { original ->
        Device.onMain { settings.stegoDefaults = StegoDefaults() }
        ActivityScenario.launch<MainActivity>(Intent(Device.context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val screen = nodes(activity.window.decorView).filterIsInstance<SettingsScreenView>().single()
                screen.navigateTo(SettingsCategory.STEGO)
                val controls = nodes(screen)
                controls.filterIsInstance<MaterialSwitch>().single { it.text == activity.getString(R.string.stego_settings_system) }.performClick()
                assertTrue(settings.stegoDefaults.systemPaste)
                controls.filterIsInstance<MaterialSwitch>().single { it.text == activity.getString(R.string.stego_settings_vault) }.performClick()
                assertFalse(settings.stegoDefaults.vaultPaste)
                controls.filterIsInstance<CheckBox>().single { "SM4" in it.text }.performClick()
                assertEquals(listOf(MessageCipher.AES, MessageCipher.SM4), settings.stegoDefaults.stages.map { it.cipher })
                val sm4 = controls.filterIsInstance<CheckBox>().single { "SM4" in it.text }
                nodes(sm4.parent as View).filterIsInstance<MaterialButton>().single {
                    it.text == activity.getString(dev.zeroinput.ime.ui.R.string.stego_earlier)
                }.performClick()
                assertEquals(listOf(MessageCipher.SM4, MessageCipher.AES), settings.stegoDefaults.stages.map { it.cipher })
                controls.filterIsInstance<MaterialButton>().single { it.text == activity.getString(R.string.stego_settings_bits_value, 256) }.performClick()
            }
            Device.instrumentation.waitForIdleSync()
            scenario.onActivity {
                val choices = nodes(dialog()).filterIsInstance<android.widget.ListView>().single()
                assertEquals(Device.context.getString(R.string.stego_settings_bit_choice, 512), choices.adapter.getItem(1).toString())
                choices.performItemClick(choices.getChildAt(1), 1, choices.adapter.getItemId(1))
                assertEquals(512, settings.stegoDefaults.masterBits)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(512, settings.stegoDefaults.masterBits)
                val screen = nodes(activity.window.decorView).filterIsInstance<SettingsScreenView>().single()
                screen.navigateTo(SettingsCategory.STEGO)
                assertTrue(nodes(screen).filterIsInstance<MaterialSwitch>().single {
                    it.text == activity.getString(R.string.stego_settings_system)
                }.isChecked)
            }
        }
    }

    @Test fun customLengthRejectsNonByteAlignedNumbersAndPersistsAValidValue() = withDefaults { original ->
        Device.onMain { settings.stegoDefaults = original.copy(masterBits = 256) }
        ActivityScenario.launch<MainActivity>(Intent(Device.context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val screen = nodes(activity.window.decorView).filterIsInstance<SettingsScreenView>().single()
                screen.navigateTo(SettingsCategory.STEGO)
                nodes(screen).filterIsInstance<MaterialButton>().single {
                    it.text == activity.getString(R.string.stego_settings_bits_value, 256)
                }.performClick()
            }
            Device.instrumentation.waitForIdleSync()
            scenario.onActivity {
                val choices = nodes(dialog()).filterIsInstance<android.widget.ListView>().single()
                choices.performItemClick(choices.getChildAt(4), 4, choices.adapter.getItemId(4))
            }
            Device.instrumentation.waitForIdleSync()
            scenario.onActivity {
                val window = dialog()
                val input = nodes(window).filterIsInstance<android.widget.EditText>().single()
                input.setText("257")
                window.findViewById<View>(android.R.id.button1).performClick()
                assertNotNull(input.error); assertEquals(256, settings.stegoDefaults.masterBits)
                input.setText("264")
                window.findViewById<View>(android.R.id.button1).performClick()
                assertEquals(264, settings.stegoDefaults.masterBits)
            }
        }
    }

    private fun withDefaults(action: (StegoDefaults) -> Unit) {
        val original = settings.stegoDefaults
        val enabled = settings.secureClipboardEnabled
        try {
            Device.onMain { settings.secureClipboardEnabled = true }
            action(original)
        } finally { Device.onMain { settings.stegoDefaults = original; settings.secureClipboardEnabled = enabled } }
    }
    private fun withWorkspace(action: (StegoPanelView, StegoWorkspace) -> Unit) {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario -> scenario.onActivity { activity ->
            val keyboard = activity.keyboard
            val graph = (activity.application as ZeroInputApplication).graph
            val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), keyboard, { true }, { null }, {}, {}, {})
            try { keyboard.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions()); action(keyboard.textStegoPanel, workspace) }
            finally { workspace.close(); keyboard.release() }
        } }
    }
    private fun dialog() = WindowInspector.getGlobalWindowViews().single {
        it.findViewById<View>(android.R.id.button2) != null || nodes(it).filterIsInstance<TextView>().any { text ->
            text.text.toString() == Device.context.getString(R.string.stego_paste_title)
        }
    }
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
