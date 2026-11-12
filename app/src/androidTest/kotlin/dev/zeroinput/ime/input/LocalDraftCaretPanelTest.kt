package dev.zeroinput.ime.input

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test

class LocalDraftCaretPanelTest {
    @Test fun longSearchKeepsTheActiveCaretVisibleWithoutEllipsis() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
        lateinit var panel: EmojiPanelView
        lateinit var body: TextView
        val query = "abcdefgh".repeat(8)
        try {
            onMain {
                panel = EmojiPanelView(ContextThemeWrapper(activity, R.style.Theme_ZeroInput_InputMethod))
                activity.keyboard.release()
                activity.setContentView(panel)
                descendants(panel).single { it.contentDescription == panel.context.getString(dev.zeroinput.ime.ui.R.string.expression_search) }.performClick()
                panel.renderQuery(query)
                panel.renderCaret(DraftCaretUi(query.length, 1), 0)
                layout(panel)
                body = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == query }
            }
            instrumentation.waitForIdleSync()
            onMain {
                val x = body.totalPaddingLeft + body.layout.getPrimaryHorizontal(query.length) - body.scrollX
                assertTrue("Active search caret must be visible", x >= 0 && x <= body.width)
                assertEquals(0, body.layout.getEllipsisCount(0))
                panel.renderCaret(DraftCaretUi(0, 2), 0)
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(0, body.scrollX)
                panel.clearSession()
            }
        } finally { onMain { activity.finish() } }
    }

    @Test fun privateMaskedPanelRejectsHiddenPrefixAndBusyTouches() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod)
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val panel = ZeroInputView(context)
        var full = false
        var busy = false
        val draft = LocalDraftInput({ graph }, { false }, {}, { text, preedit, state ->
            panel.renderPrivateDraft(text, text.isNotEmpty() || preedit.isNotEmpty(), state, busy, full)
        }, maxChars = 64, maskCommitted = true, renderCaret = panel::renderPrivateDraftCaret)
        try {
            panel.openPrivateDraft()
            panel.onKeyboardAction = draft::handle
            draft.start(InputLanguage.ENGLISH)
            draft.appendImported("abcdefg")
            layout(panel)
            val body = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == "••••efg" }
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, body.importantForAccessibility)
            assertFalse(body.isTextSelectable)
            tap(body, 0)
            draft.handle(KeyboardAction.LiteralText("X"))
            assertEquals("abcdefgX", draft.committedText())
            full = true
            draft.togglePrivateDraftVisibility()
            layout(panel)
            tap(body, 2)
            draft.handle(KeyboardAction.LiteralText("Y"))
            assertEquals("abYcdefgX", draft.committedText())
            full = false
            draft.togglePrivateDraftVisibility()
            assertEquals("••••••fgX", body.text.toString())
            busy = true
            draft.refreshDisplay()
            layout(panel)
            tap(body, 0)
            busy = false
            draft.refreshDisplay()
            draft.handle(KeyboardAction.Backspace)
            assertEquals("abcdefgX", draft.committedText())
        } finally { draft.close(); panel.release() }
    }

    @Test fun privatePanelTapPositionsTheDraftWithoutOpeningAnExternalEditor() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod)
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val panel = ZeroInputView(context)
        val draft = LocalDraftInput({ graph }, { false }, {}, { text, preedit, state ->
            panel.renderPrivateDraft(text, text.isNotEmpty() || preedit.isNotEmpty(), state, false, true)
        }, maxChars = 64, renderCaret = panel::renderPrivateDraftCaret)
        try {
            panel.openPrivateDraft()
            panel.onKeyboardAction = draft::handle
            draft.start(InputLanguage.ENGLISH)
            draft.appendImported("abcd")
            layout(panel)
            val body = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == "abcd" }
            tap(body, 2)
            draft.handle(KeyboardAction.PairedText("(", ")"))
            draft.handle(KeyboardAction.LiteralText("X"))
            assertEquals("ab(X)cd", draft.committedText())
            draft.handle(KeyboardAction.Backspace)
            assertEquals("ab()cd", draft.committedText())
        } finally { draft.close(); panel.release() }
    }

    @Test fun aiCaretTapMapsPastInlineCompositionAndRejectsChangedRenderDuringTouch() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod)
        val panel = AiWorkbenchPanelView(context)
        var action: KeyboardAction.MoveDraftCaret? = null
        panel.onDraftCaretRequested = { action = it }
        panel.renderDraftCaret(DraftCaretUi(2, 10))
        panel.renderDraft("abcd", InputLanguage.CHINESE, "ni")
        layout(panel)
        val body = descendants(panel).filterIsInstance<TextView>().single { it.id == dev.zeroinput.ime.ui.R.id.ai_draft_text }
        assertEquals("abnicd", body.text.toString())
        tap(body, 5)
        assertEquals(KeyboardAction.MoveDraftCaret(3, 10), action)
        action = null
        val now = SystemClock.uptimeMillis()
        touch(body, 1, MotionEvent.ACTION_DOWN, now)
        panel.renderDraftCaret(DraftCaretUi(1, 11))
        panel.renderDraft("changed", InputLanguage.ENGLISH)
        touch(body, 1, MotionEvent.ACTION_UP, now + 20)
        assertNull(action)
        panel.reset()
    }

    @Test fun searchPanelRoutesMiddleEditingAndClosingRejectsOldTouches() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod)
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val panel = ZeroInputView(context)
        val draft = LocalDraftInput({ graph }, { false }, {}, { text, _, state ->
            panel.renderSearchDraft(text + state.snapshot.composition, state)
        }, maxChars = 64, multiline = false, renderCaret = panel::renderSearchDraftCaret)
        try {
            panel.onSearchVisibilityChanged = { if (it) draft.start(InputLanguage.ENGLISH) else draft.close() }
            panel.onSearchAction = draft::handle
            val expressions = descendants(panel).filterIsInstance<EmojiPanelView>().single()
            visible(panel).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.expression_smileys) }.performClick()
            descendants(expressions).single { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.expression_search) }.performClick()
            draft.appendImported("abcd")
            layout(panel)
            val body = descendants(expressions).filterIsInstance<TextView>().single { it.text.toString() == "abcd" }
            tap(body, 2)
            draft.handle(KeyboardAction.PairedText("(", ")"))
            draft.handle(KeyboardAction.LiteralText("X"))
            assertEquals("ab(X)cd", draft.committedText())
            assertEquals("ab(X)cd", body.text.toString())
            val now = SystemClock.uptimeMillis()
            layout(panel)
            touch(body, 0, MotionEvent.ACTION_DOWN, now)
            expressions.closeSearch()
            touch(body, 0, MotionEvent.ACTION_UP, now + 20)
            assertEquals("", draft.committedText())
        } finally { draft.close(); panel.release() }
    }

    @Test fun dragLongPressDisabledAndReusedDraftProjectionDoNotMoveTheCaret() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod)
        val panel = AiWorkbenchPanelView(context)
        var moves = 0
        panel.onDraftCaretRequested = { moves++ }
        panel.renderDraftCaret(DraftCaretUi(2, 10))
        panel.renderDraft("abcdefgh", InputLanguage.ENGLISH)
        layout(panel)
        val body = descendants(panel).filterIsInstance<TextView>().single { it.id == dev.zeroinput.ime.ui.R.id.ai_draft_text }
        val now = SystemClock.uptimeMillis()
        touch(body, 0, MotionEvent.ACTION_DOWN, now)
        touch(body, 8, MotionEvent.ACTION_UP, now + 20)
        touch(body, 0, MotionEvent.ACTION_DOWN, now)
        touch(body, 0, MotionEvent.ACTION_UP, now + android.view.ViewConfiguration.getLongPressTimeout() + 1)
        touch(body, 0, MotionEvent.ACTION_DOWN, now)
        // Independent local drafts may have identical model revisions; a new render still retires the gesture.
        panel.renderDraftCaret(DraftCaretUi(2, 10))
        panel.renderDraft("abcdefgh", InputLanguage.ENGLISH)
        touch(body, 0, MotionEvent.ACTION_UP, now + 20)
        body.isEnabled = false
        tap(body, 0)
        assertEquals(0, moves)
        panel.reset()
    }

    private fun layout(view: View) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun tap(view: TextView, offset: Int) {
        val now = SystemClock.uptimeMillis()
        touch(view, offset, MotionEvent.ACTION_DOWN, now)
        touch(view, offset, MotionEvent.ACTION_UP, now + 20)
    }

    private fun touch(view: TextView, offset: Int, kind: Int, time: Long) {
        val textLayout = checkNotNull(view.layout)
        val line = textLayout.getLineForOffset(offset)
        val x = view.totalPaddingLeft + textLayout.getPrimaryHorizontal(offset) - view.scrollX
        val y = view.totalPaddingTop + (textLayout.getLineTop(line) + textLayout.getLineBottom(line)) / 2f - view.scrollY
        val event = MotionEvent.obtain(time, time, kind, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
