package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.button.MaterialButton
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test

class StegoPanelInteractionTest {
    @Test fun disclosuresKeepDraftsAndSelectionAcrossTypingAndResetWhenReopened() = withWorkspace { panel, workspace ->
        val layers = button(panel, R.string.stego_layers_short)
        val more = button(panel, R.string.stego_advanced)
        assertTrue(nodes(panel).filterIsInstance<CheckBox>().none { it.isShown })
        assertFalse(button(panel, R.string.stego_options).isShown)
        workspace.type(KeyboardAction.LiteralText("public cover"))
        layers.performClick()
        assertTrue(layers.isSelected)
        assertEquals(6, nodes(panel).filterIsInstance<CheckBox>().count { it.isShown })
        more.performClick()
        assertTrue(button(panel, R.string.stego_options).isShown)
        workspace.type(KeyboardAction.LiteralText("!"))
        assertTrue(layers.isSelected && more.isSelected)
        layers.performClick()
        assertTrue(nodes(panel).filterIsInstance<CheckBox>().none { it.isShown })
        assertTrue(nodes(panel).filterIsInstance<TextView>().any { it.text.toString() == "public cover!" })
        assertTrue(nodes(panel).filterIsInstance<CheckBox>().first().isChecked)
        workspace.close(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
        assertFalse(layers.isSelected || more.isSelected)
        assertFalse(nodes(panel).filterIsInstance<TextView>().any { "public cover" in it.text })
    }

    @Test fun emptyFieldsUseHintsAndPublicBodiesStayWideWithoutTakingInputFocus() = withWorkspace { panel, workspace ->
        val cover = nodes(panel).filterIsInstance<TextView>().single {
            it.hint?.toString() == it.context.getString(R.string.stego_cover_hint)
        }
        assertEquals("", cover.text.toString())
        assertFalse(cover.isFocusable)
        assertTrue(cover.performClick())
        workspace.type(KeyboardAction.LiteralText("public fixture"))
        assertEquals("public fixture", cover.text.toString())
        button(panel, R.string.stego_secret).performClick()
        workspace.type(KeyboardAction.LiteralText("public secret fixture"))
        assertFalse(nodes(panel).filterIsInstance<TextView>().any { "public secret fixture" in it.text })
        val labels = nodes(panel).filterIsInstance<TextView>().filter {
            it.text.toString() == it.context.getString(R.string.stego_editing) && it.visibility == View.VISIBLE
        }
        assertEquals(1, labels.size)
        val width = (240 * panel.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertTrue("Input uses the full available column", cover.width > width * 0.85)
    }

    @Test fun workingAndResultStatesPreventEditingAndHideUnrelatedControls() = withWorkspace { panel, _ ->
        panel.render(StegoUiState(busy = true, canInsert = true), null)
        nodes(panel).filterIsInstance<MaterialButton>().filter { it.isShown }.forEach {
            assertEquals(it.text.toString() == it.context.getString(R.string.stego_close), it.isEnabled)
        }
        panel.render(StegoUiState(prepared = true, canInsert = true), null)
        assertFalse(button(panel, R.string.stego_reveal).isShown)
        assertFalse(button(panel, R.string.stego_layers_short).isShown)
        assertTrue(button(panel, R.string.stego_edit).isShown)
        assertTrue(button(panel, R.string.stego_confirm).isShown)
        panel.render(StegoUiState(decrypt = true, hasResult = true), null)
        assertTrue(button(panel, R.string.stego_reveal).isShown)
        assertFalse(button(panel, R.string.stego_credential).isShown)
    }

    @Test fun expandingLongPublicTextDoesNotChangeTheDraftOrRevealTheSecret() = withWorkspace { panel, workspace ->
        val text = "public cover line\n".repeat(12)
        workspace.type(KeyboardAction.LiteralText(text))
        val body = nodes(panel).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == text }
        assertEquals(5, body.maxLines)
        nodes(panel).filterIsInstance<MaterialButton>().single {
            it.isShown && it.text.toString() == it.context.getString(R.string.stego_expand_text)
        }.performClick()
        assertEquals(Int.MAX_VALUE, body.maxLines)
        workspace.type(KeyboardAction.LiteralText("end"))
        assertEquals(Int.MAX_VALUE, body.maxLines)
        assertEquals(text + "end", body.text.toString())
        nodes(panel).filterIsInstance<MaterialButton>().single {
            it.isShown && it.text.toString() == it.context.getString(R.string.stego_collapse_text)
        }.performClick()
        assertEquals(5, body.maxLines)
        workspace.close(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
        assertEquals("", body.text.toString())
        assertEquals(5, body.maxLines)
    }

    private fun withWorkspace(action: (StegoPanelView, StegoWorkspace) -> Unit) {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val keyboard = activity.keyboard
                val graph = (activity.application as ZeroInputApplication).graph
                val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), keyboard, { true }, { null }, {}, {}, {})
                try {
                    keyboard.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                    action(keyboard.textStegoPanel, workspace)
                } finally { workspace.close(); keyboard.release() }
            }
        }
    }

    private fun button(panel: View, label: Int) = nodes(panel).filterIsInstance<MaterialButton>().single {
        it.text.toString() == it.context.getString(label)
    }
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
