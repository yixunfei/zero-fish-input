package dev.zeroinput.ime

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CandidateKind
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class ReleaseUiRegressionTest {
    @Test fun keyboardWrapsContentAndCanGrowAfterOpeningEmojiSearch() = withPanel { panel ->
        measure(panel)
        val keyboardHeight = panel.height
        val density = panel.resources.displayMetrics.density
        assertTrue("Keyboard must leave room for the host editor", keyboardHeight < 500 * density)
        button(panel, UiR.string.expression_smileys).performClick()
        button(panel, UiR.string.expression_search).performClick()
        measure(panel)
        assertTrue("Search must grow beyond the previous keyboard height", panel.height > keyboardHeight)
        assertTrue("Search must still leave room for the editor", panel.height < 700 * density)
        panel.returnToKeyboard()
        measure(panel)
        assertEquals(keyboardHeight, panel.height)
    }

    @Test fun diagnosticsAndAssociationsKeepReconversionReachableUntilRevoked() = withPanel { panel ->
        val ready = InputSessionState(privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE), canReconvert = true)
        var requests = 0
        panel.onReconvertRequested = { requests++ }
        panel.renderDiagnostics("Public fixture diagnostic")
        panel.renderSession(ready)
        measure(panel)
        button(panel, UiR.string.reconvert_last_word).performClick()
        assertEquals(1, requests)
        panel.renderSession(ready.copy(snapshot = EngineSnapshot(candidates = listOf(
            Candidate("public-next", "world", kind = CandidateKind.NEXT_WORD),
        ))))
        measure(panel)
        button(panel, UiR.string.reconvert_last_word).performClick()
        assertEquals(2, requests)
        panel.renderSession(ready.copy(canReconvert = false))
        assertFalse(visible(panel).any { it.contentDescription == panel.context.getString(UiR.string.reconvert_last_word) })
    }

    private fun withPanel(action: (ZeroInputView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                val target = instrumentation.targetContext
                val configuration = Configuration(target.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = 411
                    screenHeightDp = 914
                }
                val panel = ZeroInputView(ContextThemeWrapper(target.createConfigurationContext(configuration),
                    R.style.Theme_ZeroInput_InputMethod))
                try { action(panel) } finally { panel.release() }
            }
        }
        checkNotNull(result).getOrThrow()
    }

    private fun measure(view: View) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((1000 * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun button(panel: View, label: Int): View = visible(panel).first {
        it.contentDescription == panel.context.getString(label)
    }

    private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()
}
