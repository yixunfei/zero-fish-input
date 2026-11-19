package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test

class StegoCompositionCaretPlatformTest {
    @Test fun formattedCompositionTouchesMapBackToCommittedOffsetsAndHiddenOrBusyFieldsRejectThem() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(fixtureIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val keyboard = activity.keyboard
                val panel = keyboard.textStegoPanel
                try {
                    keyboard.openTextStego()
                    val moves = mutableListOf<KeyboardAction.MoveDraftCaret>()
                    panel.onCaret = { moves += it }
                    val field = StegoFieldUi("Ani hao😀B", DraftCaretUi(1, 71), 6)
                    val state = StegoUiState(fields = mapOf(StegoField.COVER to field))
                    panel.render(state, field.caret)
                    layout(keyboard)
                    val body = nodes(panel).filterIsInstance<TextView>().single { it.text.toString() == field.text }
                    for ((display, committed) in listOf(0 to 0, 1 to 1, 3 to 1, 9 to 3, 10 to 4)) {
                        tap(body, display)
                        assertEquals(KeyboardAction.MoveDraftCaret(committed, 71), moves.last())
                    }
                    val count = moves.size
                    panel.render(state.copy(busy = true), null)
                    layout(keyboard)
                    tap(body, 0)
                    assertEquals(count, moves.size)
                    panel.render(StegoUiState(field = StegoField.SECRET,
                        fields = mapOf(StegoField.SECRET to StegoFieldUi("••"))), null)
                    layout(keyboard)
                    val hidden = nodes(panel).filterIsInstance<TextView>().single { it.text.toString() == "••" }
                    tap(hidden, 0)
                    assertEquals(count, moves.size)
                } finally { keyboard.release() }
            }
        }
    }

    @Test fun actualDraftCompositionCommitsAtTheSamePositionAfterTouchingTheSuffix() {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(fixtureIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val keyboard = activity.keyboard
                keyboard.openTextStego()
                val application = activity.application as dev.zeroinput.ime.ZeroInputApplication
                val workspace = StegoWorkspace({ application.graph }, android.os.Handler(android.os.Looper.getMainLooper()),
                    keyboard, { true }, { null }, {}, {}, {}, false)
                try {
                    workspace.open(dev.zeroinput.engine.api.InputLanguage.ENGLISH, dev.zeroinput.engine.api.ChineseInputOptions())
                    @Suppress("UNCHECKED_CAST")
                    val drafts = workspace.javaClass.getDeclaredField("drafts").apply { isAccessible = true }
                        .get(workspace) as Map<StegoField, dev.zeroinput.ime.input.LocalDraftInput>
                    val draft = drafts.getValue(StegoField.COVER)
                    draft.replaceCommittedText("A😀B")
                    layout(keyboard)
                    tap(nodes(keyboard.textStegoPanel).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == "A😀B" }, 1)
                    workspace.type(KeyboardAction.Text("x"))
                    layout(keyboard)
                    val body = nodes(keyboard.textStegoPanel).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == "Ax😀B" }
                    tap(body, body.text.length)
                    // Moving completes the English candidate, whose word boundary includes a space.
                    assertEquals("Ax 😀B", draft.committedText())
                    workspace.type(KeyboardAction.LiteralText("!"))
                    assertEquals("Ax 😀B!", draft.committedText())
                } finally { workspace.close(); keyboard.release() }
            }
        }
    }

    private fun layout(view: View) {
        val metrics = view.resources.displayMetrics
        view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun fixtureIntent() = Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("night", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("auditNight") == "true")

    private fun tap(view: TextView, offset: Int) {
        val textLayout = checkNotNull(view.layout)
        val line = textLayout.getLineForOffset(offset)
        val x = view.totalPaddingLeft + textLayout.getPrimaryHorizontal(offset) - view.scrollX
        val y = view.totalPaddingTop + (textLayout.getLineTop(line) + textLayout.getLineBottom(line)) / 2f - view.scrollY
        val now = SystemClock.uptimeMillis()
        for ((kind, at) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 20)) {
            val event = MotionEvent.obtain(now, at, kind, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
