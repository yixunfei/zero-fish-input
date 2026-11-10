package dev.zeroinput.ime.ai

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.AiAssistantPanelView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.zeroinput.ime.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiAssistantPanelViewTest {
    @Test fun fullContextPreviewIncludesSelectedRoleAndMemoryAndDoesNotCallItEmpty() {
        val panel = panel()
        val role = AiPersona("id", "Public role", "Public description", "Public instruction fixture")
        val memory = AiMemory("memory", role.id, "Public memory fixture")
        panel.renderContext(AiContextState(workspace = AiWorkspaceContext(role, listOf(memory))))
        fun descendants(root: View): List<View> = listOf(root) + if (root is android.view.ViewGroup)
            (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
        descendants(panel).filterIsInstance<MaterialButton>().first {
            it.text.toString() == panel.context.getString(UiR.string.ai_context_title)
        }.performClick()
        descendants(panel).filterIsInstance<MaterialButton>().first {
            it.text.toString() == panel.context.getString(UiR.string.ai_context_preview)
        }.performClick()
        val texts = descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.any { it.contains(role.instructions) && it.contains(role.description) })
        assertTrue(texts.contains(memory.text))
        assertFalse(texts.contains(panel.context.getString(UiR.string.ai_context_empty)))
    }

    private fun panel(fontScale: Float = 1f, night: Boolean = false): AiAssistantPanelView {
        val application = RuntimeEnvironment.getApplication()
        val config = Configuration(application.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return AiAssistantPanelView(ContextThemeWrapper(application.createConfigurationContext(config), R.style.Theme_ZeroInput))
    }

    @Test fun openingTypingAndReadinessChangesNeverSubmitUntilExplicitSend() {
        val panel = panel()
        val requests = mutableListOf<String>()
        panel.onSubmit = { _, text, _ -> requests += text }
        val prompt = panel.findViewById<EditText>(UiR.id.ai_assistant_prompt)
        val send = panel.findViewById<MaterialButton>(UiR.id.ai_assistant_send)
        prompt.setText("public question")
        assertFalse(send.isEnabled)
        panel.ready("Ready", true, true)
        assertTrue(requests.isEmpty())
        send.performClick()
        assertEquals(listOf("public question"), requests)
        assertFalse(prompt.isSaveEnabled)
        assertTrue(prompt.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test fun streamCanBeStoppedAndLateContentIsClearedByTheOwner() {
        val panel = panel()
        var stops = 0
        panel.onCancel = { stops++ }
        panel.ready("Ready", true, true)
        panel.render(AiStreamEvent.Started)
        panel.render(AiStreamEvent.Delta("partial answer", "private reasoning"))
        assertTrue(panel.findViewById<MaterialButton>(UiR.id.ai_assistant_send).isEnabled)
        panel.findViewById<MaterialButton>(UiR.id.ai_assistant_stop).performClick()
        assertEquals(1, stops)
        panel.render(AiStreamEvent.Cancelled)
        assertEquals("", panel.findViewById<TextView>(UiR.id.ai_assistant_result).text.toString())
        assertFalse(panel.findViewById<MaterialButton>(UiR.id.ai_assistant_stop).isEnabled)
    }

    @Test fun largeAnswersRemainSelectableAndScrollableAtSmallSizesAndLargeFonts() {
        for (night in listOf(false, true)) {
            val panel = panel(2f, night)
            val answer = (1..40).joinToString("\n\n") { "Public answer section $it" }
            panel.render(AiStreamEvent.Completed(answer))
            panel.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 320, 480)
            assertTrue(panel.findViewById<View>(UiR.id.ai_assistant_body).canScrollVertically(1))
            val send = panel.findViewById<View>(UiR.id.ai_assistant_send)
            val rect = android.graphics.Rect()
            assertTrue(send.getGlobalVisibleRect(rect))
            assertTrue(rect.bottom <= 480)
            val result = panel.findViewById<TextView>(UiR.id.ai_assistant_result)
            assertTrue(result.isTextSelectable)
            assertEquals(answer, result.text.toString())
            assertTrue(panel.findViewById<MaterialButton>(UiR.id.ai_assistant_send).measuredWidth > 0)
            panel.clear()
            assertEquals("", result.text.toString())
        }
    }
}
