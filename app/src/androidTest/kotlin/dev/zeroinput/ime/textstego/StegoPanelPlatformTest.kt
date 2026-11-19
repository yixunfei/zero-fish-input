package dev.zeroinput.ime.textstego

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.InputEngineStatus
import dev.zeroinput.ime.ui.StegoAction
import dev.zeroinput.ime.ui.StegoUiState
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoPanelPlatformTest {
    @Test fun toolbarPlacesSingleCharacterStegoAfterShapeAndMoreAfterCommonTools() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val panel = activity.keyboard
                try {
                    panel.renderSession(InputSessionState())
                    val shape = nodes(panel).filterIsInstance<TextView>().single {
                        it.contentDescription?.toString() == it.context.getString(dev.zeroinput.ime.ui.R.string.shape_open)
                    }
                    val row = shape.parent as ViewGroup
                    val common = (0 until row.childCount).map(row::getChildAt).filter { it.visibility == View.VISIBLE }
                    val stego = common[common.indexOf(shape) + 1] as TextView
                    assertEquals("隐", stego.text.toString())
                    assertEquals(stego.context.getString(dev.zeroinput.ime.ui.R.string.stego_open), stego.contentDescription)
                    assertEquals("☰", (common.last() as TextView).text.toString())
                } finally { panel.release() }
            }
        }
    }

    @Test fun resultHasFourDirectSaveCommandsAndDraftNeverOffersCredentialSave() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val panel = activity.keyboard.textStegoPanel
                try {
                    panel.render(StegoUiState(prepared = true, canInsert = true), null)
                    val actions = mutableListOf<StegoAction>()
                    panel.onAction = { actions += it }
                    val saveLabels = listOf(dev.zeroinput.ime.ui.R.string.stego_save_plaintext,
                        dev.zeroinput.ime.ui.R.string.stego_save_cover, dev.zeroinput.ime.ui.R.string.stego_save_ciphertext,
                        dev.zeroinput.ime.ui.R.string.stego_save_carrier)
                    saveLabels.forEach { label ->
                        val button = nodes(panel).filterIsInstance<MaterialButton>().single { it.text == it.context.getString(label) }
                        assertEquals(View.VISIBLE, button.visibility)
                        button.performClick()
                    }
                    assertEquals(listOf(StegoAction.SAVE, StegoAction.SAVE_COVER, StegoAction.SAVE_CIPHERTEXT,
                        StegoAction.SAVE_CARRIER), actions)
                    panel.render(StegoUiState(field = dev.zeroinput.ime.ui.StegoField.CREDENTIAL), null)
                    assertFalse(nodes(panel).filterIsInstance<MaterialButton>().any {
                        it.visibility == View.VISIBLE && it.text == it.context.getString(dev.zeroinput.ime.ui.R.string.stego_save_field)
                    })
                } finally { activity.keyboard.release() }
            }
        }
    }

    @Test fun closingSteganographyCannotExposeItsCandidatesWhenEngineStatusReappears() {
        val secret = "public secret candidate"
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val panel = activity.keyboard
                try {
                    panel.renderSession(InputSessionState())
                    panel.openTextStego()
                    assertFalse(nodes(panel).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "☰" })
                    panel.renderTextStegoInput(InputSessionState(snapshot = EngineSnapshot("public-code", "public composition",
                        listOf(Candidate("public-candidate", secret)))))
                    assertTrue(nodes(panel).filterIsInstance<TextView>().any { it.isShown && secret in it.text })
                    panel.returnToKeyboard()
                    panel.renderEngineStatus(InputEngineStatus.FAILED)
                    assertFalse("Old private candidates must be cleared before the normal strip reappears",
                        nodes(panel).filterIsInstance<TextView>().any { it.isShown && secret in it.text })
                } finally { panel.release() }
            }
        }
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
