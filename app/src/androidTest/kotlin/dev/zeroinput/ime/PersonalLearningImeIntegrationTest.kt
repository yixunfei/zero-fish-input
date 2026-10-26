package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.personalization.QueuedPersonalizationStore
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Exercises learning through the real IME, its worker queue and native candidate routes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PersonalLearningImeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun repeatedRealClicksPromoteAndPreserveNativeRouteAcrossInputCodes() {
        val original = shell("settings get secure default_input_method").trim()
        val previous = graph.settings.privacyConfiguration()
        val pack = graph.settings.lastLanguagePackKey
        val options = graph.settings.chineseInputOptions
        val model = graph.settings.experimentalModelRanking
        val language = graph.settings.lastLanguage
        var activity: InputFixtureActivity? = null
        try {
            graph.engineExecutor.submit {}.get(60, TimeUnit.SECONDS)
            onMain {
                graph.settings.learningEnabled = true
                graph.settings.incognitoMode = false
                graph.settings.experimentalModelRanking = false
                graph.settings.chineseInputOptions = ChineseInputOptions()
                graph.settings.lastLanguagePackKey = null
            }
            (graph.personalization as QueuedPersonalizationStore).clearAndAwait()
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method")
            shell("ime set $method")
            val editor = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("input_type", InputType.TYPE_CLASS_TEXT)
                .putExtra("ime_options", EditorInfo.IME_ACTION_DONE)) as InputFixtureActivity
            activity = editor
            await { panelOrNull() != null }
            onMain {
                editor.editor.imeOptions = EditorInfo.IME_ACTION_DONE
                editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
            }
            instrumentation.waitForIdleSync()
            onMain {
                if (views(panel()).filterIsInstance<TextView>().none { it.isShown && it.text.toString() == "中" }) {
                    panel().onKeyboardAction(KeyboardAction.SwitchLanguage)
                }
            }
            await { views(panel()).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "中" } }
            await { panel().renderedEngineStatus == dev.zeroinput.ime.ui.InputEngineStatus.READY }
            repeat(3) { round ->
                type("ceshi")
                choose("测试")
                await { editor.editor.text.toString() == "测试".repeat(round + 1) }
            }
            type("cs")
            assertEquals("测试", words().firstOrNull())
            choose("测试")
            await { editor.editor.text.toString() == "测试".repeat(4) }
            type("cs")
            assertEquals("测试", words().firstOrNull())
        } finally {
            activity?.let { onMain { it.finish() } }
            runCatching { (graph.personalization as QueuedPersonalizationStore).clearAndAwait() }
            onMain {
                graph.settings.learningEnabled = previous.learningEnabled
                graph.settings.incognitoMode = previous.incognitoMode
                graph.settings.experimentalModelRanking = model
                graph.settings.lastLanguage = language
                graph.settings.lastLanguagePackKey = pack
                graph.settings.chineseInputOptions = options
            }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    private fun type(value: String) = onMain {
        value.forEach { panel().onKeyboardAction(KeyboardAction.Text(it.toString())) }
    }

    private fun choose(value: String) {
        await { value in words() }
        onMain { candidates().first { it.text.toString() == value }.performClick() }
    }

    private fun candidates() = views(panel()).filterIsInstance<TextView>()
        .filter { it.isShown && it.javaClass.simpleName == "CandidateItemView" }

    private fun words() = candidates().map { it.text.toString() }

    private fun panelOrNull() = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }

    private fun panel() = checkNotNull(panelOrNull())

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        var diagnostics = "panel=missing"
        onMain { panelOrNull()?.let { diagnostics = "candidateCount=${words().size} engine=${it.renderedEngineStatus}" } }
        fail("Personal-learning IME fixture did not reach expected state: $diagnostics")
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
