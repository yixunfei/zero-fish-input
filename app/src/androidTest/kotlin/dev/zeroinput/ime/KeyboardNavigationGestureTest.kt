package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.EmojiPanelView
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class KeyboardNavigationGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ready = InputSessionState(privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE))

    @Test fun backClosesEmojiSearchThenEmojiThenYieldsToTheSystem() = withPanel { panel ->
        tap(panel, UiR.string.expression_smileys)
        tap(panel, UiR.string.expression_search)
        assertTrue(panel.navigateBack())
        assertNotNull(find(panel, UiR.string.expression_search))
        assertTrue(panel.navigateBack())
        assertFalse(panel.canNavigateBack)
        assertFalse(panel.navigateBack())
    }

    @Test fun toolsAndExpandedCandidatesReturnWithoutCancellingComposition() = withPanel { panel ->
        val commands = mutableListOf<Any>()
        panel.onKeyboardAction = { commands += it }
        panel.renderSession(ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("public", "你")))))
        tap(panel, UiR.string.expand_candidates)
        tap(panel, UiR.string.keyboard_tools)
        assertTrue(panel.navigateBack())
        assertNotNull(find(panel, UiR.string.collapse_candidates))
        assertTrue(panel.navigateBack())
        assertNotNull(find(panel, UiR.string.expand_candidates))
        assertFalse(panel.canNavigateBack)
        assertTrue(commands.isEmpty())
    }

    @Test fun horizontalEmojiSwipeChangesCategoryWithoutInsertingAnEmoji() = withPanel { panel ->
        var commits = 0
        panel.onEmojiSelected = { commits++ }
        tap(panel, UiR.string.expression_smileys)
        val emoji = descendants(panel).filterIsInstance<EmojiPanelView>().single()
        val tabs = descendants(emoji).filter { it.isSelected && it.contentDescription != null }
        assertTrue(tabs.isNotEmpty())
        val grid = descendants(emoji).filterIsInstance<RecyclerView>().single()
        swipe(grid.parent as ViewGroup, -1)
        val changed = descendants(emoji).filter { it.isSelected && it.contentDescription != null }
        assertNotEquals(tabs.map { it.contentDescription }, changed.map { it.contentDescription })
        swipe(grid.parent as ViewGroup, 1)
        assertEquals(tabs.map { it.contentDescription }, descendants(emoji)
            .filter { it.isSelected && it.contentDescription != null }.map { it.contentDescription })
        assertEquals(0, commits)
    }

    @Test fun candidateSwipesRequestPagesAndCancelledDragsDoNothing() = withPanel { panel ->
        val pages = mutableListOf<PageDirection>()
        var selections = 0
        panel.onCandidatePageChanged = { pages += it }
        panel.onCandidateSelected = { _, _ -> selections++ }
        panel.renderSession(ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("public", "你")),
            hasPreviousPage = true, hasNextPage = true)))
        tap(panel, UiR.string.expand_candidates)
        val grid = descendants(panel).filterIsInstance<RecyclerView>().first { it.isShown }
        swipe(grid.parent as ViewGroup, -1)
        swipe(grid.parent as ViewGroup, 1)
        swipe(grid.parent as ViewGroup, -1, cancel = true)
        assertEquals(listOf(PageDirection.NEXT, PageDirection.PREVIOUS), pages)
        assertEquals(0, selections)
    }

    @Test fun firstExpandedCandidateSwipeRequestsNextPageWhenTheFirstGridFits() = withPanel { panel ->
        val pages = mutableListOf<PageDirection>()
        val firstPage = List(1) { Candidate("first", "首页") }
        panel.onCandidatePageChanged = { direction ->
            pages += direction
            panel.renderSession(ready.copy(snapshot = EngineSnapshot(
                "ni", "ni", firstPage + Candidate("second", "后续"),
                hasPreviousPage = true,
            )))
        }
        panel.renderSession(ready.copy(snapshot = EngineSnapshot(
            "ni", "ni", firstPage, hasNextPage = true,
        )))
        tap(panel, UiR.string.expand_candidates)
        val grid = descendants(panel).filterIsInstance<RecyclerView>().first { it.isShown }
        swipeVertical(grid.parent as ViewGroup, -1)
        assertEquals(listOf(PageDirection.NEXT), pages)
        assertNotNull(descendants(panel).firstOrNull {
            it.isShown && it.contentDescription == panel.context.getString(UiR.string.candidate_description, "后续")
        })
    }

    @Test fun cancelledVerticalCandidateSwipeDoesNotRequestPage() = withPanel { panel ->
        val pages = mutableListOf<PageDirection>()
        panel.onCandidatePageChanged = { pages += it }
        panel.renderSession(ready.copy(snapshot = EngineSnapshot(
            "ni", "ni", listOf(Candidate("first", "首页")), hasNextPage = true,
        )))
        tap(panel, UiR.string.expand_candidates)
        val grid = descendants(panel).filterIsInstance<RecyclerView>().first { it.isShown }
        swipeVertical(grid.parent as ViewGroup, -1, cancel = true)
        assertTrue(pages.isEmpty())
    }

    @Test fun compactCandidateStripLoadsTheNextPageAtItsEdge() = withPanel { panel ->
        var pages = 0
        panel.onCandidatePageChanged = { pages++ }
        panel.renderSession(ready.copy(snapshot = EngineSnapshot("ni", "ni", listOf(Candidate("public", "你")), hasNextPage = true)))
        panel.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, 1080, panel.measuredHeight)
        val scroll = descendants(panel).filterIsInstance<HorizontalScrollView>().first { it.isShown }
        swipe(scroll.parent as ViewGroup, -1)
        assertEquals(1, pages)
    }

    @Test fun fuzzyShortcutReflectsSavedRuleStateAndIsHiddenForSensitiveEditors() = withPanel { panel ->
        val configured = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)
        val state = ready.copy(engineDescriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE),
            capabilities = setOf(EngineCapability.FUZZY_PINYIN)))
        panel.renderSession(state)
        panel.renderChineseOptions(configured)
        var toggles = 0
        panel.onFuzzySwitchRequested = { toggles++; panel.renderChineseOptions(configured.copy(fuzzyPinyinEnabled = false)) }
        tap(panel, UiR.string.fuzzy_disable)
        assertEquals(1, toggles)
        assertNotNull(find(panel, UiR.string.fuzzy_enable))
        panel.renderSession(state.copy(privacy = SessionPrivacy(true, false, false, PrivacyReason.PASSWORD_FIELD)))
        assertNull(find(panel, UiR.string.fuzzy_enable))
    }

    @Test fun unconfiguredFuzzyShortcutOpensRuleSettingsWithoutEnablingEverything() = withPanel { panel ->
        panel.renderSession(ready.copy(engineDescriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE),
            capabilities = setOf(EngineCapability.FUZZY_PINYIN))))
        panel.renderChineseOptions(ChineseInputOptions())
        var settings = 0
        var toggles = 0
        panel.onFuzzySettingsRequested = { settings++ }
        panel.onFuzzySwitchRequested = { toggles++ }
        tap(panel, UiR.string.fuzzy_choose_rules)
        assertEquals(1, settings)
        assertEquals(0, toggles)
    }

    private fun withPanel(action: (ZeroInputView) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try {
            instrumentation.waitForIdleSync()
            onMain { activity.keyboard.renderSession(ready); action(activity.keyboard) }
        } finally { onMain { activity.keyboard.release(); activity.finish() } }
    }

    private fun swipe(view: ViewGroup, direction: Int, cancel: Boolean = false) {
        val width = view.width.coerceAtLeast(800).toFloat()
        val start = width * if (direction < 0) 0.8f else 0.2f
        val end = width - start
        val y = view.height / 2f
        val down = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP).withIndex()) {
            MotionEvent.obtain(down, down + index * 60, action, if (index == 0) start else end, y, 0).also {
                view.dispatchTouchEvent(it); it.recycle()
            }
        }
    }

    private fun swipeVertical(view: ViewGroup, direction: Int, cancel: Boolean = false) {
        val height = view.height.coerceAtLeast(200).toFloat()
        val start = height * if (direction < 0) 0.8f else 0.2f
        val end = height - start
        val x = view.width / 2f
        val down = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP).withIndex()) {
            MotionEvent.obtain(down, down + index * 60, action, x, if (index == 0) start else end, 0).also {
                view.dispatchTouchEvent(it); it.recycle()
            }
        }
    }

    private fun tap(root: View, label: Int) { checkNotNull(find(root, label)).performClick() }
    private fun find(root: View, label: Int) = descendants(root).firstOrNull {
        it.isShown && it.contentDescription == it.context.getString(label)
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
