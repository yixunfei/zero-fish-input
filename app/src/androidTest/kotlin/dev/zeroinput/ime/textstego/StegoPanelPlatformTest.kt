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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoPanelPlatformTest {
    @Test fun closingSteganographyCannotExposeItsCandidatesWhenEngineStatusReappears() {
        val secret = "public secret candidate"
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val panel = activity.keyboard
                try {
                    panel.renderSession(InputSessionState())
                    panel.openTextStego()
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
