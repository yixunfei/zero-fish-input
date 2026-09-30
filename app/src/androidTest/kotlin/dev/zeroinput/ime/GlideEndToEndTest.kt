package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class GlideEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun allFiveGlideLayoutsDecodeAndCommitThroughTheRealEditor() {
        val originalMethod = shell("settings get secure default_input_method").trim()
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val originalOptions = settings.chineseInputOptions
        val originalEnabled = settings.glideTypingEnabled
        val originalLearning = settings.learningEnabled
        var activity: InputFixtureActivity? = null
        try {
            onMain { settings.glideTypingEnabled = true; settings.learningEnabled = false }
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method"); shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            for (layout in GlideLayout.entries) {
                await { panel() != null }
                onMain {
                    settings.chineseInputOptions = originalOptions.copy(
                        keyboardLayout = if (layout == GlideLayout.PINYIN_NINE_KEY) ChineseKeyboardLayout.NINE_KEY else ChineseKeyboardLayout.FULL,
                        doublePinyinScheme = when (layout) {
                            GlideLayout.DOUBLE_PINYIN_MICROSOFT -> DoublePinyinScheme.MICROSOFT
                            GlideLayout.DOUBLE_PINYIN_ZIRANMA -> DoublePinyinScheme.ZIRANMA
                            else -> DoublePinyinScheme.OFF
                        })
                    val isEnglish = requireNotNull(panel()).availableGlideLayout == GlideLayout.ENGLISH_QWERTY
                    if (isEnglish != (layout == GlideLayout.ENGLISH_QWERTY)) requireNotNull(panel()).onKeyboardAction(KeyboardAction.SwitchLanguage)
                }
                await { panel()?.availableGlideLayout == layout &&
                    (layout == GlideLayout.ENGLISH_QWERTY || panel()?.renderedEngineStatus == InputEngineStatus.READY) }
                val code = when (layout) {
                    GlideLayout.ENGLISH_QWERTY -> "hello"
                    GlideLayout.PINYIN_QWERTY -> "nihao"
                    GlideLayout.PINYIN_NINE_KEY -> "64426"
                    else -> "nihk"
                }
                onMain { trace(views(requireNotNull(panel())).filterIsInstance<KeyboardPanel>().single(), code) }
                await {
                    visibleText().any { it.text.toString() == "hello" || it.text.toString().contains("ni hao") }
                }
                onMain { visibleText().first { it.text.toString() == "hello" || it.text.toString().contains("ni hao") }.performClick() }
                if (layout == GlideLayout.ENGLISH_QWERTY) await { activity.editor.text.toString() == "hello " }
                else {
                    await { visibleText().any { it.text.toString() == "你好" } }
                    onMain { visibleText().first { it.text.toString() == "你好" }.performClick() }
                    await { activity.editor.text.toString() == "你好" }
                }
                onMain {
                    activity.editor.setText("")
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(activity.editor)
                }
            }
        } finally {
            onMain {
                settings.chineseInputOptions = originalOptions
                settings.glideTypingEnabled = originalEnabled
                settings.learningEnabled = originalLearning
            }
            activity?.let { onMain { it.finish() } }
            if (originalMethod.isNotBlank() && originalMethod != "null") shell("ime set $originalMethod")
        }
    }

    private fun trace(keyboard: KeyboardPanel, code: String) {
        val points = code.map { char ->
            val key = views(keyboard).filterIsInstance<TextView>().first {
                it.text.toString() == char.toString() || it.text.toString().startsWith("$char ") }
            val bounds = Rect()
            key.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(key, bounds)
            bounds.exactCenterX() to bounds.exactCenterY()
        }
        val start = SystemClock.uptimeMillis()
        points.forEachIndexed { index, point ->
            send(keyboard, start, start + index * 30, if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, point)
        }
        send(keyboard, start, start + points.size * 30, MotionEvent.ACTION_UP, points.last())
    }
    private fun send(view: View, start: Long, time: Long, action: Int, point: Pair<Float, Float>) {
        val event = MotionEvent.obtain(start, time, action, point.first, point.second, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun panel() = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }
    private fun visibleText() = panel()?.let(::views).orEmpty().filterIsInstance<TextView>().filter { it.isShown }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Public glide fixture did not reach expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
