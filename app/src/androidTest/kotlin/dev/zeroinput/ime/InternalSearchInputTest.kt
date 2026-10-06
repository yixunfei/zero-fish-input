package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.testing.DeviceTouch
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardPanel
import dev.zeroinput.ime.ui.KeyboardPlacement
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class InternalSearchInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun realSearchConvertsChineseWithoutWritingIntoTheExternalEditorAndClearsOnSessionChange() = withEditor { activity ->
        openToolsIfNeeded()
        DeviceTouch.tap { labelled(UiR.string.expression_smileys) }
        DeviceTouch.tapVisible { labelled(UiR.string.expression_search_input) }
        await { panel()?.isSearchEditing == true }
        val english = onMain { keys().filterIsInstance<TextView>().any { it.text.toString() == "En" } }
        if (english) DeviceTouch.tapVisible { keys().filterIsInstance<TextView>().first { it.text.toString() == "En" } }
        for (letter in "nihao") DeviceTouch.tapVisible {
            views(checkNotNull(panel())).filterIsInstance<KeyboardPanel>().flatMap(::views).filterIsInstance<TextView>()
                .first { it.isShown && it.text.toString() == letter.toString() }
        }
        await { views(checkNotNull(panel())).any {
            it.isShown && it.contentDescription == it.context.getString(UiR.string.candidate_description, "你好")
        } }
        DeviceTouch.tap { views(checkNotNull(panel())).first {
            it.isShown && it.contentDescription == it.context.getString(UiR.string.candidate_description, "你好")
        } }
        await { (labelled(UiR.string.expression_search_input) as TextView).text.toString() == "你好" }
        onMain { assertTrue("Search must never compose or commit into the host editor", activity.editor.text.isEmpty()) }
        DeviceTouch.tapVisible { labelled(UiR.string.expression_clear_search) }
        await { (labelled(UiR.string.expression_search_input) as TextView).text.toString() != "你好" }
        DeviceTouch.tapVisible { views(checkNotNull(panel())).filterIsInstance<KeyboardPanel>().flatMap(::views)
            .filterIsInstance<TextView>().first { it.isShown && it.text.toString() == "n" } }
        onMain { activity.getSystemService(InputMethodManager::class.java).restartInput(activity.editor) }
        await { panel()?.isSearchEditing == false }
        onMain { assertTrue(activity.editor.text.isEmpty()) }
        openToolsIfNeeded()
        DeviceTouch.tap { labelled(UiR.string.expression_smileys) }
        DeviceTouch.tapVisible { labelled(UiR.string.expression_search_input) }
        await { panel()?.isSearchEditing == true }
        onMain {
            assertEquals(labelled(UiR.string.expression_search_input).context.getString(UiR.string.expression_search_hint),
                (labelled(UiR.string.expression_search_input) as TextView).text.toString())
        }
    }

    @Test fun realHandwritingFullscreenGrowsTheImeWindowAndReturnsToItsOriginalSize() = withEditor {
        openToolsIfNeeded()
        DeviceTouch.tap { labelled(UiR.string.handwriting_open) }
        val compact = onMain { checkNotNull(panel()).height }
        DeviceTouch.tapVisible { labelled(UiR.string.handwriting_fullscreen) }
        await { checkNotNull(panel()).height > compact * 1.5f }
        onMain {
            val panel = checkNotNull(panel())
            assertTrue(panel.height >= panel.rootView.height * 0.8f)
        }
        DeviceTouch.tapVisible { labelled(UiR.string.handwriting_exit_fullscreen) }
        await { checkNotNull(panel()).height == compact }
    }

    private fun withEditor(action: (InputFixtureActivity) -> Unit) {
        val original = shell("settings get secure default_input_method").trim()
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val language = settings.lastLanguage
        val placement = settings.keyboardPlacement(false)
        var activity: InputFixtureActivity? = null
        try {
            onMain { settings.lastLanguage = InputLanguage.CHINESE; settings.saveKeyboardPlacement(false, KeyboardPlacement()) }
            shell("ime enable dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService")
            shell("ime set dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            await { panel() != null }
            action(activity)
        } finally {
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
            onMain { settings.lastLanguage = language; settings.saveKeyboardPlacement(false, placement) }
        }
    }

    private fun panel() = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }
    private fun keys() = views(checkNotNull(panel())).filterIsInstance<KeyboardPanel>().flatMap(::views).filter { it.isShown }
    private fun openToolsIfNeeded() {
        if (onMain { views(checkNotNull(panel())).any { it.isShown && it.contentDescription == it.context.getString(UiR.string.keyboard_tools) } }) {
            DeviceTouch.tapVisible { labelled(UiR.string.keyboard_tools) }
        }
    }
    private fun labelled(id: Int) = views(checkNotNull(panel())).first {
        it.isShown && it.contentDescription == it.context.getString(id)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Public internal-input fixture did not reach the expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
