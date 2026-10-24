package dev.zeroinput.ime

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.testing.DeviceTouch
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class KeyboardToolbarRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ready = InputSessionState(privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE))

    @Test fun idleCompositionAndToolsKeepTheSameHeaderAndKeyPositions() = withPanel { panel ->
        val header = onMain { toolbarHeader(panel) }
        val height = onMain { header.height }
        val keyTop = onMain { keyTop(panel) }
        onMain {
            assertTrue(height == dp(panel, 48) || height == dp(panel, 96))
            assertEquals(dp(panel, 48), checkNotNull(find(panel, UiR.string.keyboard_tools)).height)
        }
        onMain {
            panel.renderSession(ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("public:0", "你")))))
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(height, header.height); assertEquals(keyTop, keyTop(panel)) }
        onMain { checkNotNull(find(panel, UiR.string.keyboard_tools)).performClick() }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(height, header.height); assertEquals(keyTop, keyTop(panel)) }
        onMain { panel.renderSession(ready) }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(height, header.height); assertEquals(keyTop, keyTop(panel)) }
    }

    private fun keyTop(panel: ZeroInputView): Int {
        val key = visible(panel).filterIsInstance<TextView>().first { it.text.toString() == "q" }
        return IntArray(2).also(key::getLocationOnScreen)[1]
    }

    @Test fun hideControlStaysAtTheRightAndRequestsHideWithoutChangingComposition() = withPanel { panel ->
        var requests = 0
        onMain {
            panel.onHideKeyboardRequested = { requests++ }
            val hide = checkNotNull(find(panel, UiR.string.keyboard_hide))
            assertEquals(dp(panel, 48), hide.width)
            assertEquals((hide.parent as View).width, hide.right)
            hide.performClick()
            assertEquals(1, requests)
            panel.renderSession(ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("public:0", "你")))))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val hide = checkNotNull(find(panel, UiR.string.keyboard_hide))
            assertEquals((hide.parent as View).width, hide.right)
            hide.performClick()
            assertEquals(2, requests)
            assertTrue(visible(panel).filterIsInstance<TextView>().any { it.text.toString() == "你" })
        }
    }

    @Test fun aiIsDirectWhenIdleAndFirstInToolsDuringComposition() = withPanel { panel ->
        capturePublicPreview("ai-entry.png")
        DeviceTouch.tapVisible { checkNotNull(find(panel, UiR.string.ai_open)) }
        capturePublicPreview("ai-workbench.png")
        onMain {
            assertTrue(panel.isAiOpen)
            panel.returnToKeyboard()
            panel.renderSession(ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("fixture", "你")))))
        }
        DeviceTouch.tapVisible { checkNotNull(find(panel, UiR.string.keyboard_tools)) }
        DeviceTouch.tapVisible { checkNotNull(find(panel, UiR.string.ai_open)) }
        onMain { assertTrue(panel.isAiOpen) }
    }

    @Test fun aiToolbarEntryFitsBeforeScrollingWithAllChineseControls() = withPanel { panel ->
        onMain {
            panel.renderSession(ready.copy(engineDescriptor = dev.zeroinput.engine.api.EngineDescriptor(
                id = "public-engine", displayName = "Public engine", version = "1",
                languages = setOf(dev.zeroinput.engine.api.InputLanguage.CHINESE),
                capabilities = setOf(dev.zeroinput.engine.api.EngineCapability.CHINESE_SCRIPT,
                    dev.zeroinput.engine.api.EngineCapability.NINE_KEY_PINYIN))))
        }
        DeviceTouch.tapVisible { checkNotNull(find(panel, UiR.string.ai_open)) }
        onMain { assertTrue(panel.isAiOpen) }
    }

    @Test fun privacyRevocationMarksAiUnavailableAndRejectsStaleClicks() = withPanel { panel ->
        onMain {
            val entry = checkNotNull(find(panel, UiR.string.ai_open))
            panel.renderSession(InputSessionState(privacy = SessionPrivacy(false, true, false, PrivacyReason.INCOGNITO_MODE)))
            assertNull(find(panel, UiR.string.ai_open))
            assertNotNull(find(panel, UiR.string.ai_entry_unavailable))
            entry.performClick()
            assertFalse(panel.isAiOpen)
        }
        tap(panel, UiR.string.keyboard_tools)
        DeviceTouch.tapVisible { checkNotNull(find(panel, UiR.string.ai_entry_unavailable)) }
        onMain { assertNull(find(panel, UiR.string.ai_open)); assertFalse(panel.isAiOpen) }
    }

    @Test fun privacyChangeDuringAiClickCannotOpenWorkbench() = withPanel { panel ->
        onMain {
            panel.onUserInteraction = {
                panel.renderSession(InputSessionState(privacy = SessionPrivacy(false, true, false, PrivacyReason.INCOGNITO_MODE)))
            }
        }
        DeviceTouch.tapVisible { checkNotNull(find(panel, UiR.string.ai_open)) }
        onMain { assertFalse(panel.isAiOpen); assertNull(find(panel, UiR.string.ai_open)) }
    }

    @Test fun toolsOpenAndRemainOpenAfterUnchangedRender() = withPanel { panel ->
        tap(panel, UiR.string.keyboard_tools)
        onMain {
            assertNotNull("Tools must expose the AI entry", find(panel, UiR.string.ai_open))
            panel.renderSession(ready)
            assertNotNull("An idle render must not close tools", find(panel, UiR.string.ai_open))
        }
        capturePublicPreview("ai-tools.png")
        tap(panel, UiR.string.ai_open)
        onMain { assertTrue(panel.isAiOpen); assertTrue(panel.isAiEditing) }
    }

    @Test fun toolsOpenWhileAiCandidatesExistAndReachOtherPanels() = withPanel { panel ->
        tap(panel, UiR.string.ai_open)
        onMain {
            assertTrue(panel.isAiOpen)
            panel.renderAiDraft("ni", ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("fixture", "你")))))
        }
        tap(panel, UiR.string.keyboard_tools)
        onMain { assertNotNull("AI candidates must yield to tools", find(panel, UiR.string.secure_clipboard_open)) }
        tap(panel, UiR.string.secure_clipboard_open)
        onMain { assertFalse(panel.isAiOpen); assertNotNull(find(panel, UiR.string.keyboard_return)) }
    }

    @Test fun secondaryPanelControlsRemainVisible() = withPanel { panel ->
        tap(panel, UiR.string.expression_smileys)
        onMain {
            assertNotNull("Secondary panels keep their toolbar", find(panel, UiR.string.keyboard_return))
        }
        tap(panel, UiR.string.keyboard_return)
        onMain { assertNotNull(find(panel, UiR.string.keyboard_tools)) }
    }

    private fun withPanel(test: (ZeroInputView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try {
            onMain { activity.keyboard.renderSession(ready) }
            instrumentation.waitForIdleSync()
            test(activity.keyboard)
        } finally { onMain { activity.keyboard.release(); activity.finish() } }
    }

    private fun tap(panel: ZeroInputView, label: Int) = dev.zeroinput.ime.testing.DeviceTouch.tap {
        checkNotNull(find(panel, label)) { "Expected visible toolbar control" }
    }

    private fun capturePublicPreview(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAiEntry") != "true") return
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200)
        // This class only captures its attached, empty preview, with fixed public metadata.
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "ai-entry-fixtures").apply { mkdirs() }
            java.io.File(folder, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }

    private fun find(root: View, label: Int): View? = visible(root).firstOrNull {
        it.contentDescription == it.context.getString(label)
    }
    private fun toolbarHeader(panel: ZeroInputView): View {
        val tool = checkNotNull(find(panel, UiR.string.keyboard_tools))
        val scroll = (tool.parent as View).parent as HorizontalScrollView
        return scroll.parent as View
    }
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun visible(root: View): List<View> = if (!root.isShown) emptyList() else
        listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { visible(root.getChildAt(it)) } else emptyList()
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
