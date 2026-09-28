package dev.zeroinput.ime

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.ime.settings.ChineseEngineChoice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class InputPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realInputWindowLatencyUsesOnlyPublicFixtures() {
        val originalMethod = shell("settings get secure default_input_method").trim()
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val originalOptions = settings.chineseInputOptions
        val originalEngine = settings.chineseEngine
        val originalPack = settings.lastLanguagePackKey
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            onMain {
                settings.chineseInputOptions = ChineseInputOptions()
                settings.chineseEngine = ChineseEngineChoice.RIME
                settings.lastLanguagePackKey = null
            }
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            val panel = awaitPanel()
            onMain {
                if (descendants(panel).filterIsInstance<android.widget.TextView>().any {
                    it.contentDescription == "切换中英文" && it.text.toString() == "En"
                }) panel.onKeyboardAction(dev.zeroinput.ime.ui.KeyboardAction.SwitchLanguage)
            }
            awaitReady(panel)
            val dispatch = mutableListOf<Long>()
            val editorTimes = mutableListOf<Long>()
            val frames = mutableListOf<Long>()
            repeat(3) {
                for (key in "nihaozhongguoshijie") measureKey(panel, activity, key.toString(), dispatch, editorTimes, frames)
                onMain { panel.onClearCompositionRequested() }
            }
            report("dispatch", dispatch)
            report("editor", editorTimes)
            report("frame", frames)
            verifyBurst(panel, activity, "nihao".map(Char::toString), "rime-full")
            verifyReconversion(panel, activity)
            onMain { panel.onLayoutSwitchRequested() }
            awaitReady(panel)
            instrumentation.waitForIdleSync()
            verifyBurst(panel, activity, listOf("6 MNO", "4 GHI", "4 GHI", "2 ABC", "6 MNO"), "rime-nine")
            onMain { settings.chineseEngine = ChineseEngineChoice.DICTIONARY_TEST }
            awaitReady(panel)
            instrumentation.waitForIdleSync()
            verifyBurst(panel, activity, "nihao".map(Char::toString), "dictionary-full")
        } finally {
            activity?.let { onMain { it.finish() } }
            if (originalMethod.isNotBlank() && originalMethod != "null") shell("ime set $originalMethod")
            onMain {
                settings.chineseInputOptions = originalOptions
                settings.chineseEngine = originalEngine
                settings.lastLanguagePackKey = originalPack
            }
        }
    }

    private fun measureKey(panel: ZeroInputView, activity: InputFixtureActivity, label: String,
        dispatch: MutableList<Long>, editors: MutableList<Long>, frames: MutableList<Long>) {
        val edited = CountDownLatch(1)
        val framed = CountDownLatch(1)
        val start = AtomicLong()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, offset: Int, before: Int, count: Int) {
                if (edited.count == 0L) return
                editors += System.nanoTime() - start.get()
                edited.countDown()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        onMain {
            activity.editor.addTextChangedListener(watcher)
            start.set(System.nanoTime())
            sendKey(panel, label)
            dispatch += System.nanoTime() - start.get()
            Choreographer.getInstance().postFrameCallback {
                frames += System.nanoTime() - start.get()
                framed.countDown()
            }
        }
        assertTrue("A fixture key must reach the editor", edited.await(2, TimeUnit.SECONDS))
        assertTrue("A fixture key must reach a frame", framed.await(2, TimeUnit.SECONDS))
        onMain { activity.editor.removeTextChangedListener(watcher) }
    }

    private fun verifyReconversion(panel: ZeroInputView, activity: InputFixtureActivity) {
        var expected = ""
        onMain {
            expected = activity.editor.text.toString() + "你好"
            "nihao".forEach { sendKey(panel, it.toString()) }
            sendKey(panel, "↵")
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(activity.editor.text.toString() == expected)
            val label = panel.context.getString(dev.zeroinput.ime.ui.R.string.reconvert_last_word)
            descendants(panel).firstOrNull { it.isShown && it.contentDescription == label }?.performClick()
                ?: panel.onReconvertRequested()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(activity.editor.text) == expected.length - 2)
            sendKey(panel, "↵")
        }
        instrumentation.waitForIdleSync()
        onMain { assertTrue(activity.editor.text.toString() == expected) }
    }

    private fun verifyBurst(panel: ZeroInputView, activity: InputFixtureActivity, labels: List<String>, phase: String) {
        var expected = ""
        val completed = CountDownLatch(1)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.toString() == expected) completed.countDown()
            }
        }
        onMain {
            // Replacing the editor's Editable would invalidate its InputConnection before this burst.
            expected = activity.editor.text.toString() + "你好".repeat(10)
            activity.editor.addTextChangedListener(watcher)
            repeat(10) {
                labels.forEach { sendKey(panel, it) }
                sendKey(panel, "↵")
            }
        }
        val delivered = completed.await(3, TimeUnit.SECONDS)
        onMain {
            val output = activity.editor.text.toString()
            activity.editor.removeTextChangedListener(watcher)
            assertTrue("Rapid burst must commit every fixture phrase: $phase, length=${output.length}", delivered && output == expected)
        }
    }

    private fun sendKey(panel: ZeroInputView, label: String) {
        val key = descendants(panel).first { it.isShown && (it.contentDescription?.toString() == label ||
            (it is android.widget.TextView && it.text.toString() == label)) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
            key.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun awaitPanel(): ZeroInputView {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var panel: ZeroInputView? = null
            onMain { panel = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown } }
            panel?.let { return it }
            SystemClock.sleep(100)
        }
        error("Input fixture keyboard did not open")
    }

    private fun awaitReady(panel: ZeroInputView) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = panel.renderedEngineStatus == dev.zeroinput.ime.ui.InputEngineStatus.READY }
            if (ready) return
            SystemClock.sleep(100)
        }
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        error("Input fixture engine was not ready: ${graph.rime.runtime.state.name}")
    }

    private fun report(prefix: String, samples: List<Long>) {
        val values = samples.sorted()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("${prefix}_p50_us", (values[values.size / 2] / 1000).toString())
            putString("${prefix}_p95_us", (values[values.size * 95 / 100] / 1000).toString())
            putString("${prefix}_max_us", (values.last() / 1000).toString())
        })
    }

    private fun descendants(view: View): List<View> = if (view is ViewGroup) {
        listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else listOf(view)

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
