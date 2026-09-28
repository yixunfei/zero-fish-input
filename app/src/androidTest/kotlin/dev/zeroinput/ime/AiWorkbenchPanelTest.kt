package dev.zeroinput.ime

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.AiStreamEvent
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.ui.AiWorkbenchPanelView
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiWorkbenchPanelTest {
    @Test fun draftAndResultsRemainInsidePanelAcrossThemesAndRotation() = onMain {
        for (night in listOf(false, true)) for (landscape in listOf(false, true)) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val configuration = Configuration(target.resources.configuration).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) 800 else 320
                screenHeightDp = if (landscape) 411 else 640
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_ZeroInput_InputMethod)
            val panel = ZeroInputView(context)
            panel.renderSession(InputSessionState(privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE)))
            visible(panel).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.ai_open) }.performClick()
            assertTrue(panel.isAiOpen)
            assertTrue(panel.isAiEditing)
            val workbench = visible(panel).filterIsInstance<AiWorkbenchPanelView>().single()
            var inserts = 0
            panel.onAiInsert = { inserts++ }
            panel.renderAiDraft("public fixture", InputSessionState())
            panel.renderAi(AiStreamEvent.Started)
            panel.renderAi(AiStreamEvent.Completed("public response"))
            assertEquals(0, inserts)
            measure(panel, configuration.screenWidthDp, configuration.screenHeightDp)
            assertTrue(workbench.height > 0)
            assertTrue(panel.measuredHeight <= (configuration.screenHeightDp * context.resources.displayMetrics.density).toInt())
            visible(workbench).filterIsInstance<TextView>().first {
                it.text == context.getString(dev.zeroinput.ime.ui.R.string.ai_insert)
            }.performClick()
            assertEquals(1, inserts)
            panel.renderSession(InputSessionState(privacy = SessionPrivacy(false, true, false, PrivacyReason.INCOGNITO_MODE)))
            assertFalse(panel.isAiOpen)
            assertFalse(visible(panel).any { it is AiWorkbenchPanelView })
            panel.release()
        }
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
