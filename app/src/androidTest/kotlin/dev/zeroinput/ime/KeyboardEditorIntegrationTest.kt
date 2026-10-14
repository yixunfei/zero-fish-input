package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class KeyboardEditorIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pinyinCursorEditsCommitExactlyOnceToTheSameEditor() {
        val original = shell("settings get secure default_input_method").trim()
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            await { findKey(UiR.string.key_shift) != null && views(panel()).any { it.isShown && it is TextView && it.text.toString() == "c" } }
            onMain {
                val language = checkNotNull(findKey(UiR.string.key_language)) as TextView
                if (language.text.toString() == "En") language.performClick()
            }
            await { (findKey(UiR.string.key_language) as? TextView)?.text.toString() == "中" }
            onMain { for (letter in "chaxun") key(panel(), letter.toString()).performClick() }
            await { views(panel()).any { it.isShown && it.contentDescription == it.context.getString(UiR.string.select_single_syllable) } }
            onMain {
                val composition = views(panel()).filterIsInstance<TextView>().single { it.javaClass.simpleName == "CompositionEditorView" }
                assertTrue(composition.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    android.os.Bundle().apply {
                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
                    }))
                key(panel(), "e").performClick()
                assertEquals("cexun", composition.text.toString())
                checkNotNull(findKey(UiR.string.key_enter)).performClick()
            }
            await { activity.editor.text.toString() == "cexun" }
            onMain { assertEquals(5, activity.editor.selectionStart); assertEquals(5, activity.editor.selectionEnd) }
        } finally {
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    @Test fun numericInputAndEditorActionFollowRestartedEditorConfiguration() {
        val original = shell("settings get secure default_input_method").trim()
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("input_type", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                .putExtra("ime_options", EditorInfo.IME_ACTION_DONE)) as InputFixtureActivity
            await { findKey(UiR.string.key_done) != null }
            onMain { for (label in listOf("-", "1", ".", "2")) key(panel(), label).performClick() }
            await { activity.editor.text.toString() == "-1.2" }
            onMain { checkNotNull(findKey(UiR.string.key_done)).performClick() }
            await { activity.editorActions.contains(EditorInfo.IME_ACTION_DONE) }
            onMain {
                activity.editor.setText("")
                activity.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                activity.editor.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                activity.getSystemService(InputMethodManager::class.java).restartInput(activity.editor)
            }
            await { findKey(UiR.string.key_enter) != null && views(panel()).any { it is TextView && it.text.toString() == "q" } }
            onMain { checkNotNull(findKey(UiR.string.key_enter)).performClick() }
            await { activity.editor.text.toString() == "\n" }
            onMain { assertFalse(activity.editorActions.contains(EditorInfo.IME_ACTION_SEARCH)) }
        } finally {
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    @Test fun timedShiftHoldLocksCaseAndCancelledHoldDoesNotLock() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        val actions = mutableListOf<KeyboardAction>()
        try {
            instrumentation.waitForIdleSync()
            val shift = onMainValue { key(activity.keyboard, activity.getString(UiR.string.key_shift)) }
            onMain {
                activity.keyboard.onKeyboardAction = { actions += it }
                touch(shift, MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
            onMain {
                touch(shift, MotionEvent.ACTION_UP)
                key(activity.keyboard, "Q").performClick()
                key(activity.keyboard, "W").performClick()
                assertEquals(listOf(KeyboardAction.Text("Q"), KeyboardAction.Text("W")), actions)
                shift.performClick()
                touch(shift, MotionEvent.ACTION_DOWN)
                touch(shift, MotionEvent.ACTION_CANCEL)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
            onMain { assertNotNull(key(activity.keyboard, "q")) }
        } finally { onMain { activity.finish() } }
    }

    private fun panel() = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<ZeroInputView>().first { it.isShown }
    private fun findKey(label: Int): View? = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().filter { it.isShown }.flatMap(::views)
        .firstOrNull { it.isShown && it.contentDescription?.toString() == it.context.getString(label) }
    private fun key(root: View, label: String) = views(root).first {
        it.isShown && (it.contentDescription?.toString() == label || it is TextView && it.text.toString() == label) }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMainValue(condition)) return
            SystemClock.sleep(50)
        }
        fail("Public editor fixture did not reach the expected state")
    }
    private fun onMain(action: () -> Unit) { onMainValue(action) }
    private fun <T> onMainValue(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
