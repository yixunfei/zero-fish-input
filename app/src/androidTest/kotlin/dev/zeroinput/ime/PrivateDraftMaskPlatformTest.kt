package dev.zeroinput.ime

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
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.input.PrivateDraftInput
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateDraftMaskPlatformTest {
    @Test fun oneAndTwoCharacterDraftsAreFullyMaskedUntilExplicitlyRevealed() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val graph = (Device.context.applicationContext as ZeroInputApplication).graph
                val view = activity.keyboard
                val input = PrivateDraftInput({ graph }, Handler(Looper.getMainLooper()), { true }, { null }, {}, {})
                try {
                    view.openPrivateDraft(); input.open(view, InputLanguage.ENGLISH, ChineseInputOptions())
                    for (piece in listOf("甲", "乙", "🌏")) {
                        input.type(KeyboardAction.LiteralText(piece))
                        assertFalse(labels(view).any { piece in it })
                    }
                    assertTrue(labels(view).contains("••••"))
                    input.toggleVisibility()
                    assertTrue(labels(view).contains("甲乙🌏"))
                    input.toggleVisibility()
                    assertFalse(labels(view).any { "甲乙" in it })
                    input.hide(); input.open(view, InputLanguage.ENGLISH, ChineseInputOptions())
                    input.type(KeyboardAction.LiteralText("丙"))
                    assertFalse(labels(view).any { "丙" in it })
                } finally { input.close(); view.release() }
            }
        }
    }
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = nodes(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
}
