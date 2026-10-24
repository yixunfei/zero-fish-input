package dev.zeroinput.ime.ai

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ai.api.AiStreamEvent
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.AiWorkbenchPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.zeroinput.ime.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiWorkbenchPanelViewTest {
    private val context: Context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.Theme_ZeroInput_InputMethod)

    @Test fun longAnswerScrollsSelectsAndInsertsWholeOrChosenSectionsWithoutReasoning() {
        val panel = AiWorkbenchPanelView(context)
        val inserts = mutableListOf<Insert>()
        val thinkingChanges = mutableListOf<Boolean>()
        panel.onInsert = { text, includeThinking, sections -> inserts += Insert(text, includeThinking, sections) }
        panel.onThinkingVisibilityChanged = thinkingChanges::add
        val answer = (1..24).joinToString("\n\n") { "section $it" }
        val reasoning = "separate reasoning fixture"
        panel.render(AiStreamEvent.Completed(answer, reasoning))
        measure(panel)

        val result = panel.findViewById<TextView>(UiR.id.ai_result_text)
        assertTrue(result.isTextSelectable)
        assertTrue(panel.findViewById<ScrollView>(UiR.id.ai_result_viewport).canScrollVertically(1))
        val thoughtSwitch = panel.findViewById<MaterialSwitch>(UiR.id.ai_thinking_toggle)
        val thoughtText = panel.findViewById<TextView>(UiR.id.ai_thinking_text)
        assertTrue(thoughtSwitch.isChecked)
        assertEquals(View.VISIBLE, thoughtText.visibility)
        assertTrue(thoughtText.text.toString().contains(reasoning))

        thoughtSwitch.performClick()
        assertFalse(thoughtSwitch.isChecked)
        assertEquals(View.GONE, thoughtText.visibility)
        assertEquals(listOf(false), thinkingChanges)
        thoughtSwitch.performClick()
        assertTrue(thoughtSwitch.isChecked)
        assertEquals(View.VISIBLE, thoughtText.visibility)

        panel.findViewById<MaterialButton>(UiR.id.ai_insert_result).performClick()
        assertEquals(Insert(answer, false, null), inserts.single())

        panel.findViewById<MaterialButton>(UiR.id.ai_result_sections_toggle).performClick()
        val sections = panel.findViewById<View>(UiR.id.ai_result_sections)
        assertNotNull(sections)
        assertEquals(View.VISIBLE, sections.visibility)
        val chosen = descendants(sections).filterIsInstance<MaterialCheckBox>().single { it.text == "section 12" }
        chosen.performClick()
        panel.findViewById<MaterialButton>(UiR.id.ai_insert_result).performClick()
        assertEquals(Insert(answer, false, listOf(11)), inserts.last())
    }

    private fun measure(view: View) {
        val density = view.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (520 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private data class Insert(val text: String, val includeThinking: Boolean, val sections: List<Int>?)
}
