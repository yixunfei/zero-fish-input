package dev.zeroinput.ime

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
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
    @Test fun newChatIsAvailableDuringEditingAndGenerationAndDoesNotSend() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod)
        val panel = AiWorkbenchPanelView(context)
        var chats = 0
        var sends = 0
        panel.onNewConversation = { chats++; panel.render(AiStreamEvent.Cancelled); panel.renderDraft("") }
        panel.onSubmit = { _, _, _ -> sends++ }
        for (generating in listOf(false, true)) {
            panel.setEditing(true)
            panel.renderDraft("public draft")
            if (generating) panel.render(AiStreamEvent.Started)
            val newChat = visible(panel).filterIsInstance<TextView>().single {
                it.text == context.getString(dev.zeroinput.ime.ui.R.string.ai_new_conversation) }
            assertTrue(newChat.isEnabled)
            newChat.performClick()
            assertTrue(panel.editing)
        }
        assertEquals(2, chats)
        assertEquals(0, sends)
    }

    @Test fun selectingTranslateRequiresExplicitSendAndConversationDoesNotEraseResult() = onMain {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ContextThemeWrapper(target, R.style.Theme_ZeroInput_InputMethod)
        val panel = AiWorkbenchPanelView(context)
        var requests = 0
        var inserted = ""
        panel.onSubmit = { _, _, _ -> requests++ }
        panel.onInsert = { inserted = it }
        panel.renderDraft("public fixture")
        visible(panel).filterIsInstance<TextView>().first {
            it.text == context.getString(dev.zeroinput.ime.ui.R.string.ai_action_translate)
        }.performClick()
        assertEquals(0, requests)
        panel.render(AiStreamEvent.Started)
        panel.renderConversation(dev.zeroinput.ai.api.AiConversation(title = "public fixture"))
        panel.render(AiStreamEvent.Completed("public response"))
        visible(panel).filterIsInstance<TextView>().first {
            it.text == context.getString(dev.zeroinput.ime.ui.R.string.ai_insert)
        }.performClick()
        assertEquals("public response", inserted)
    }

    @Test fun draftAndResultsRemainInsidePanelAcrossThemesAndRotation() = onMain {
        for (night in listOf(false, true)) for (landscape in listOf(false, true)) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val configuration = Configuration(target.resources.configuration).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) 640 else 320
                screenHeightDp = if (landscape) 320 else 640
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
            panel.renderAiModels(listOf("public-long-model-name", "public-other"), "public-long-model-name")
            captureAndCheck(panel, configuration, night, "draft")
            panel.renderAi(AiStreamEvent.Started)
            panel.renderAi(AiStreamEvent.Completed("public response"))
            assertEquals(0, inserts)
            measure(panel, configuration.screenWidthDp, configuration.screenHeightDp)
            captureAndCheck(panel, configuration, night, "result")
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

    private fun captureAndCheck(panel: ZeroInputView, configuration: Configuration, night: Boolean, stage: String) {
        measure(panel, configuration.screenWidthDp, configuration.screenHeightDp)
        val workbench = visible(panel).filterIsInstance<AiWorkbenchPanelView>().single()
        val selectedAction = visible(workbench).filterIsInstance<MaterialButton>().single { it.isChecked }
        val container = checkNotNull(selectedAction.backgroundTintList)
            .getColorForState(selectedAction.drawableState, 0)
        assertTrue("Selected action text must remain readable", ColorUtils.calculateContrast(
            selectedAction.currentTextColor, container) >= 4.5)
        val labels = listOf(dev.zeroinput.ime.ui.R.string.ai_submit, dev.zeroinput.ime.ui.R.string.ai_insert,
            dev.zeroinput.ime.ui.R.string.ai_new_conversation,
            if (panel.isAiEditing) dev.zeroinput.ime.ui.R.string.ai_read else dev.zeroinput.ime.ui.R.string.ai_edit)
        for (label in labels) {
            val button = visible(workbench).filterIsInstance<TextView>().first { it.text == it.context.getString(label) }
            val rect = Rect().also { button.getDrawingRect(it); workbench.offsetDescendantRectToMyCoords(button, it) }
            assertTrue("Command must stay inside the workbench", rect.top >= 0 && rect.bottom <= workbench.height &&
                rect.left >= 0 && rect.right <= workbench.width)
            assertTrue("Command must remain a full touch target", button.height >= 48 * button.resources.displayMetrics.density)
            val layout = checkNotNull(button.layout)
            assertTrue("Command text must fit", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
        }
        visible(panel).forEach { it.viewTreeObserver.dispatchOnPreDraw() }
        val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        try {
            panel.draw(Canvas(image))
            val more = visible(workbench).single {
                it.contentDescription == it.context.getString(dev.zeroinput.ime.ui.R.string.ai_more)
            }
            val rect = Rect().also { more.getDrawingRect(it); panel.offsetDescendantRectToMyCoords(more, it) }
            val radius = (12 * more.resources.displayMetrics.density).toInt()
            val pixels = IntArray(radius * radius * 4)
            image.getPixels(pixels, 0, radius * 2, rect.centerX() - radius, rect.centerY() - radius, radius * 2, radius * 2)
            assertTrue("More command must render a visible icon", pixels.distinct().size > 1)
            val folder = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "ai-interaction-fixtures").apply { mkdirs() }
            java.io.File(folder, "ai-$stage-${configuration.screenWidthDp}-${if (night) "dark" else "light"}.png")
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
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
