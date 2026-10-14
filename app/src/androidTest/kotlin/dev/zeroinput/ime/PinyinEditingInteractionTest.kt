package dev.zeroinput.ime

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class PinyinEditingInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun snapshot(input: String = "chaxun", range: CompositionSelection = CompositionSelection(input.length)) =
        EngineSnapshot(input, input, listOf(Candidate("public", "查询")), canSelectSyllable = true, selection = range)

    @Test fun shiftBecomesSegmentOnlyForEditablePinyinAndToolsHaveNoDuplicateEntry() = withPanel { panel ->
        onMain {
            val actions = mutableListOf<KeyboardAction>()
            panel.onKeyboardAction = { actions += it }
            panel.renderSession(InputSessionState(snapshot = snapshot()))
            val split = descendants(panel).filterIsInstance<TextView>().single {
                it.isShown && it.text == panel.context.getString(UiR.string.key_segment)
            }
            assertFalse(split.isLongClickable)
            split.performClick()
            assertEquals(listOf(KeyboardAction.SelectSyllable), actions)
            click(panel, UiR.string.keyboard_tools)
            assertFalse(descendants(panel).any { it.isShown && it.contentDescription == panel.context.getString(UiR.string.select_single_syllable) && it !== split })
            panel.navigateBack()
            panel.renderSession(InputSessionState(language = InputLanguage.ENGLISH))
            assertNotNull(descendants(panel).firstOrNull { it.isShown && it.contentDescription == panel.context.getString(UiR.string.key_shift) })
        }
    }

    @Test fun tapAndDragMoveTheCaretAndAccessibilityCanSelectARange() = withPanel { panel ->
        val selections = mutableListOf<CompositionSelection>()
        onMain {
            panel.onCompositionSelection = { range, input ->
                selections += range
                panel.renderSession(InputSessionState(snapshot = snapshot(input, range)))
            }
            panel.renderSession(InputSessionState(snapshot = snapshot()))
            measure(panel)
            val editor = editor(panel)
            touch(editor, MotionEvent.ACTION_DOWN, 1)
            touch(editor, MotionEvent.ACTION_UP, 1)
            assertEquals(CompositionSelection(1), selections.last())
            touch(editor, MotionEvent.ACTION_DOWN, 1)
            touch(editor, MotionEvent.ACTION_MOVE, 4)
            touch(editor, MotionEvent.ACTION_UP, 4)
            assertEquals(CompositionSelection(4), selections.last())
            assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
            }))
            assertEquals(CompositionSelection(1, 3), selections.last())
            assertFalse(editor.isTextSelectable)
        }
    }

    @Test fun apostropheFromTheSymbolsPageSplitsAnActiveChineseComposition() = withPanel { panel ->
        val actions = mutableListOf<KeyboardAction>()
        onMain {
            panel.onKeyboardAction = { actions += it }
            panel.renderSession(InputSessionState(snapshot = snapshot("ch", CompositionSelection(1))))
            click(panel, UiR.string.key_symbols)
            val apostrophe = descendants(panel).single {
                it.isShown && it.contentDescription == "'"
            }
            apostrophe.performClick()
        }
        assertEquals(listOf(KeyboardAction.Text("'")), actions)
    }

    @Test fun splitKeyRendersTheNewReadingAtTheTappedCaret() = withPanel { panel ->
        onMain {
            val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
            graph.createImmediateEngine(InputLanguage.CHINESE).use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                (engine as CompositionEditingEngine).restoreComposition("ch")
                panel.onCompositionSelection = { selection, _ ->
                    (engine as CompositionCursorEngine).selectComposition(selection)
                    panel.renderSession(InputSessionState(snapshot = engine.snapshot))
                }
                panel.onKeyboardAction = { action ->
                    assertEquals(KeyboardAction.SelectSyllable, action)
                    engine.selectSyllable()
                    panel.renderSession(InputSessionState(snapshot = engine.snapshot))
                }
                panel.renderSession(InputSessionState(snapshot = engine.snapshot))
                measure(panel)
                touch(editor(panel), MotionEvent.ACTION_DOWN, 1)
                touch(editor(panel), MotionEvent.ACTION_UP, 1)
                click(panel, UiR.string.select_single_syllable)
                assertEquals("c'h", editor(panel).text.toString())
                assertEquals(CompositionSelection(2), engine.snapshot.selection)
            }
        }
    }

    @Test fun longPressDragSelectsRangeAndInputChangeCancelsOldGesture() = withPanel { panel ->
        val selections = mutableListOf<CompositionSelection>()
        onMain {
            panel.onCompositionSelection = { range, _ -> selections += range }
            panel.renderSession(InputSessionState(snapshot = snapshot()))
            measure(panel)
            touch(editor(panel), MotionEvent.ACTION_DOWN, 1)
        }
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
        onMain {
            touch(editor(panel), MotionEvent.ACTION_MOVE, 4)
            touch(editor(panel), MotionEvent.ACTION_UP, 4)
            assertEquals(CompositionSelection(1, 4), selections.single())
            touch(editor(panel), MotionEvent.ACTION_DOWN, 1)
            panel.renderSession(InputSessionState(snapshot = snapshot("nihao")))
            touch(editor(panel), MotionEvent.ACTION_UP, 1)
            assertEquals(1, selections.size)
        }
    }

    @Test fun stripAndExpandedCandidatesLoadWithoutButtonsAndKeepSelectionRoutes() = withPanel { panel ->
        var loaded = 0
        var selected: String? = null
        val items = mutableListOf(Candidate("public:0", "词"))
        fun state() = InputSessionState(snapshot = snapshot().copy(candidates = items.toList(), hasNextPage = loaded < 8))
        onMain {
            panel.onCandidatePageChanged = { direction ->
                if (direction == PageDirection.NEXT && loaded < 8) {
                    loaded++
                    items += List(8) { Candidate("public:$loaded:$it", "词$loaded$it") }
                    panel.renderSession(state())
                }
            }
            panel.onCandidateSelected = { _, id -> selected = id }
            panel.renderSession(state())
            measure(panel)
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue("A short initial row must load enough candidates for browsing", loaded > 0)
            val scroller = descendants(panel).filterIsInstance<HorizontalScrollView>().single { it.isShown }
            scroller.scrollTo(scroller.getChildAt(0).width, 0)
        }
        instrumentation.waitForIdleSync()
        onMain { click(panel, UiR.string.expand_candidates); measure(panel) }
        instrumentation.waitForIdleSync()
        onMain {
            val grid = descendants(panel).filterIsInstance<RecyclerView>().single { it.isShown }
            assertTrue(grid.childCount > 0)
            assertEquals(1, (grid.parent as ViewGroup).childCount)
            grid.getChildAt(0).performClick()
            assertNotNull(selected)
        }
    }

    private fun withPanel(action: (ZeroInputView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            KeyboardPreviewFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try { instrumentation.waitForIdleSync(); action(activity.keyboard) }
        finally { onMain { activity.keyboard.release(); activity.finish() } }
    }

    private fun editor(panel: ZeroInputView) = descendants(panel).filterIsInstance<TextView>()
        .single { it.javaClass.simpleName == "CompositionEditorView" }

    private fun touch(view: TextView, action: Int, offset: Int) {
        val x = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset) - view.scrollX
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, view.height / 2f, 0).also {
            view.dispatchTouchEvent(it); it.recycle()
        }
    }

    private fun click(panel: ZeroInputView, label: Int) = descendants(panel).first {
        it.isShown && it.contentDescription == panel.context.getString(label)
    }.performClick()

    private fun measure(panel: View) {
        val density = panel.resources.displayMetrics.density
        panel.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
