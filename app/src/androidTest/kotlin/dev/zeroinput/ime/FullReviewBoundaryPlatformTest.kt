package dev.zeroinput.ime

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.ui.CandidateStripView
import dev.zeroinput.ime.ui.DraftCaretUi
import dev.zeroinput.ime.ui.EmojiPanelView
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test

/** Public fixtures verify the concrete failure examples from the static audit. */
class FullReviewBoundaryPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput_InputMethod)

    @Test fun openingDeviceAgentSettingsDoesNotGrantMissingCategories() {
        val target = instrumentation.targetContext
        val graph = (target.applicationContext as ZeroInputApplication).graph
        val preferences = target.getSharedPreferences("zeroinput-settings", android.content.Context.MODE_PRIVATE)
        val key = "ai.device-agent.categories"
        val existed = preferences.contains(key)
        val previous = preferences.getStringSet(key, null)?.toSet()
        var activity: android.app.Activity? = null
        try {
            assertTrue(preferences.edit().remove(key).commit())
            activity = instrumentation.startActivitySync(android.content.Intent(target,
                dev.zeroinput.ime.ai.device.DeviceAgentSettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue(graph.settings.deviceAgentAllowedCategories.isEmpty())
                assertFalse(preferences.contains(key))
            }
        } finally {
            activity?.let { owner -> onMain { owner.finish() } }
            val edit = preferences.edit()
            if (existed) edit.putStringSet(key, previous) else edit.remove(key)
            assertTrue(edit.commit())
        }
    }

    @Test fun segmentedCompositionMapsEveryRawCaretThroughSpacesAndTheSeparator() = onMain {
        checkCompositionOffsets("")
    }

    @Test fun matchedReadingAnnotationDoesNotDisplaceEditableOffsets() = onMain {
        checkCompositionOffsets("hang")
    }

    @Test fun formattedCompositionLongerThanCommittedSearchTextKeepsTheWholeDraft() = onMain {
        val panel = ZeroInputView(context)
        try {
            descendants(panel).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.expression_smileys) }
                .performClick()
            val emoji = descendants(panel).filterIsInstance<EmojiPanelView>().single()
            descendants(emoji).single { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.expression_search) }
                .performClick()
            assertTrue(panel.isSearchEditing)
            val state = InputSessionState(snapshot = EngineSnapshot("nihao", "ni hao"))
            // This is the production callback contract: text includes this same snapshot's preedit.
            panel.renderSearchDraftCaret(DraftCaretUi(1, 1))
            panel.renderSearchDraft("AB" + state.snapshot.composition, state)
            assertTrue(descendants(emoji).filterIsInstance<TextView>().any { it.text.toString() == "Ani haoB" })
            panel.renderSearchDraft("AB", state.copy(snapshot = EngineSnapshot.Empty))
            assertTrue(descendants(emoji).filterIsInstance<TextView>().any { it.text.toString() == "AB" })
        } finally { panel.release() }
    }

    private fun checkCompositionOffsets(reading: String) {
        val strip = CandidateStripView(context)
        val raw = "nihaoxiang"
        val editable = "ni hao · xiang"
        val snapshot = EngineSnapshot(raw, raw, listOf(Candidate("fixture", "好", comment = reading,
            pinyinMatch = PinyinCandidateMatch("hao", 2, 5, true))), selection = CompositionSelection(0))
        val displayOffsets = listOf(0, 1, 3, 4, 5, 9, 10, 11, 12, 13, 14)
        var selected: CompositionSelection? = null
        strip.onCompositionSelection = { range, input -> assertEquals(raw, input); selected = range }
        for ((rawOffset, displayOffset) in displayOffsets.withIndex()) {
            strip.render(snapshot.copy(selection = CompositionSelection(rawOffset)))
            layout(strip)
            val body = descendants(strip).filterIsInstance<TextView>().single { it.text.startsWith(editable) }
            val info = body.createAccessibilityNodeInfo()
            try {
                assertEquals(displayOffset, info.textSelectionStart)
                assertEquals(displayOffset, info.textSelectionEnd)
            } finally { info.recycle() }
            selected = null
            val now = SystemClock.uptimeMillis()
            val x = body.totalPaddingLeft + body.layout.getPrimaryHorizontal(displayOffset) - body.scrollX
            val y = body.totalPaddingTop + body.layout.height / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + 10, action, x, y, 0)
                try { assertTrue(body.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            assertEquals(CompositionSelection(rawOffset), selected)
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, displayOffset)
                putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, displayOffset)
            }
            assertTrue(body.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, arguments))
            assertEquals(CompositionSelection(rawOffset), selected)
        }
        if (reading.isNotEmpty()) {
            val body = descendants(strip).filterIsInstance<TextView>().single { it.text.startsWith(editable) }
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, 1)
                putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, body.text.length)
            }
            assertTrue(body.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, arguments))
            assertEquals(CompositionSelection(1, raw.length), selected)
        }
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec((400 * view.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((96 * view.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
