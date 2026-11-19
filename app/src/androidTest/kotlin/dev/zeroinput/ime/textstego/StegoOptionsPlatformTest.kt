package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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

@RunWith(AndroidJUnit4::class)
class StegoOptionsPlatformTest {
    @Test fun inlineCheckboxesKeepSelectionOrderAndRevokedCallbacksCannotMutateOptions() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val graph = (Device.context.applicationContext as ZeroInputApplication).graph
                var active = true
                val view = activity.keyboard
                val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { active }, { null }, {}, {}, {})
                try {
                    view.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                    val checks = nodes(view.textStegoPanel).filterIsInstance<CheckBox>()
                    assertEquals(6, checks.size)
                    val aes = checks.single { "AES" in it.text }
                    val sm4 = checks.single { "SM4" in it.text }
                    val chacha = checks.single { "XChaCha20" in it.text }
                    assertTrue(aes.isChecked)
                    aes.performClick(); sm4.performClick(); chacha.performClick(); aes.performClick()
                    assertEquals("1. SM4-GCM", sm4.text.toString())
                    assertEquals("2. XChaCha20-Poly1305", chacha.text.toString())
                    assertEquals("3. AES-GCM", aes.text.toString())
                    val earlier = nodes(view.textStegoPanel).single {
                        it.contentDescription?.toString() == activity.getString(dev.zeroinput.ime.ui.R.string.stego_earlier_for, "AES-GCM")
                    }
                    earlier.performClick()
                    assertEquals("2. AES-GCM", aes.text.toString())
                    val stale = view.textStegoPanel.onSelectAlgorithm
                    active = false
                    stale(1, false)
                    assertEquals("2. AES-GCM", aes.text.toString())
                    active = true
                    workspace.close(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                    stale(1, false)
                    assertTrue(aes.isChecked)
                } finally { workspace.close(); view.release() }
            }
        }
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
