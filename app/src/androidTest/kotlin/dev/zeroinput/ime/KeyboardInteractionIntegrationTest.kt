package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class KeyboardInteractionIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun systemBackReturnsThroughEmojiSearchAndPanelBeforeHidingTheIme() = withEditor {
        onMain { find(UiR.string.keyboard_tools)?.performClick() }
        tap(UiR.string.expression_smileys)
        tap(UiR.string.expression_search)
        back()
        await { panelOrNull() != null && find(UiR.string.expression_search) != null }
        back()
        await { panelOrNull()?.canNavigateBack == false }
        back()
        await { panelOrNull() == null }
    }

    @Test fun externalCursorMoveKeepsTextAndDeletesAtTheNewPosition() = withEditor { activity ->
        onMain { for (letter in "nihao") key(letter.toString()).performClick() }
        await { activity.editor.text.length >= 5 }
        val original = onMain { activity.editor.text.toString() }
        onMain { activity.editor.setSelection(2) }
        await { find(UiR.string.select_single_syllable) == null }
        onMain { key(activity.getString(UiR.string.key_backspace)).performClick() }
        await { activity.editor.text.toString() == original.removeRange(1, 2) }
        onMain { assertEquals(1, activity.editor.selectionStart) }
    }

    @Test fun screenSwipeChangesEmojiCategoryWithoutCommittingText() = withEditor { activity ->
        onMain { find(UiR.string.keyboard_tools)?.performClick() }
        tap(UiR.string.expression_smileys)
        tap(UiR.string.expression_custom)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val previous = onMain { selectedEmojiCategory() }
        var touches = 0
        onMain {
            val panel = checkNotNull(panelOrNull())
            val original = panel.onTouchStarted
            panel.onTouchStarted = { touches++; original() }
        }
        val bounds = onMain {
            val grid = views(checkNotNull(panelOrNull())).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
                .first { it.isShown }
            val position = IntArray(2).also(grid::getLocationOnScreen)
            android.graphics.Rect(position[0], position[1], position[0] + grid.width, position[1] + grid.height)
        }
        val start = bounds.left + bounds.width() * 4 / 5
        val end = bounds.left + bounds.width() / 5
        injectSwipe(start, end, bounds.centerY())
        assertEquals("Synthetic swipe must reach the IME: " + bounds, 1, onMain { touches })
        await { selectedEmojiCategory() != previous }
        onMain { assertTrue(activity.editor.text.isEmpty()) }
    }

    private fun selectedEmojiCategory(): String = views(checkNotNull(panelOrNull()))
        .filterIsInstance<dev.zeroinput.ime.ui.EmojiPanelView>().single().let(::views)
        .first { it.isShown && it.isSelected && it.contentDescription != null }.contentDescription.toString()

    private fun withEditor(action: (InputFixtureActivity) -> Unit) {
        val originalIme = shell("settings get secure default_input_method").trim()
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        val options = graph.settings.chineseInputOptions
        val language = graph.settings.lastLanguage
        val learning = graph.settings.learningEnabled
        val pack = graph.settings.lastLanguagePackKey
        var activity: InputFixtureActivity? = null
        try {
            onMain {
                graph.settings.chineseInputOptions = ChineseInputOptions()
                graph.settings.lastLanguage = InputLanguage.CHINESE
                graph.settings.lastLanguagePackKey = null
                graph.settings.learningEnabled = false
            }
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("input_type", InputType.TYPE_CLASS_TEXT)
                .putExtra("ime_options", EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) as InputFixtureActivity
            await { panelOrNull() != null }
            action(activity)
        } finally {
            activity?.let { onMain { it.finish() } }
            onMain {
                graph.settings.chineseInputOptions = options
                graph.settings.lastLanguage = language
                graph.settings.lastLanguagePackKey = pack
                graph.settings.learningEnabled = learning
            }
            if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
        }
    }

    private fun panelOrNull(): ZeroInputView? = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }
    private fun find(label: Int): View? = panelOrNull()?.let(::views)?.firstOrNull {
        it.isShown && it.contentDescription == it.context.getString(label)
    }
    private fun tap(label: Int) { onMain { checkNotNull(find(label)).performClick() }; instrumentation.waitForIdleSync() }
    private fun back() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        if (InstrumentationRegistry.getArguments().getString("edgeBack") == "true") {
            val display = instrumentation.targetContext.resources.displayMetrics
            // Android excludes the IME's bottom region from system back gestures.
            val y = onMain {
                val position = IntArray(2)
                checkNotNull(panelOrNull()).getLocationOnScreen(position)
                (position[1] / 2).coerceAtLeast(32)
            }
            val end = display.widthPixels * 3 / 4
            injectSwipe(1, end, y)
        } else shell("input keyevent 4")
    }

    private fun injectSwipe(start: Int, end: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..16) {
            val action = when (step) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                16 -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val x = start + (end - start) * step / 16f
            android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y.toFloat(), 0).also { event ->
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue("Synthetic touch injection must succeed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            SystemClock.sleep(16)
        }
    }
    private fun key(label: String): View = views(checkNotNull(panelOrNull())).first {
        it.isShown && (it.contentDescription == label || it is TextView && it.text.toString() == label)
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        val state = onMain {
            "panel=" + (panelOrNull() != null) + ", search=" + (find(UiR.string.expression_search) != null) +
                ", closeSearch=" + (find(UiR.string.expression_close_search) != null)
        }
        if (InstrumentationRegistry.getArguments().getString("captureInteractions") == "true") {
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "interaction-failure.png")
                    .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
        }
        fail("Public fixture did not reach the expected interaction state: " + state)
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
