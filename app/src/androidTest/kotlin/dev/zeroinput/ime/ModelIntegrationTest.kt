package dev.zeroinput.ime

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.ime.input.AndroidEditorConnection
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.model.RobertaMiniScorer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic fixtures only; no captured editor text is persisted or logged. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ModelIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun productionMiniMatchesFrozenNumericFixtureAndBounds() {
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        // Separate the model measurement from the application's asynchronous Rime warm-up.
        graph.engineExecutor.submit {}.get(60, java.util.concurrent.TimeUnit.SECONDS)
        val beforePss = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss
        val started = System.nanoTime()
        RobertaMiniScorer(instrumentation.targetContext).use { scorer ->
            val initialized = (System.nanoTime() - started) / 1_000_000.0
            val prefix = "我买了一件新".toCharArray()
            val words = arrayOf("衣服".toCharArray(), "依附".toCharArray())
            val result = checkNotNull(scorer.score(prefix, words) { false })
            assertArrayEquals(floatArrayOf(-2.2595852f, -14.597835f), result, .025f)
            assertNull(scorer.score(prefix, words) { true })
            assertNull(scorer.score(CharArray(17) { '学' }, words) { false })
            assertNull(scorer.score(prefix, arrayOf("长词组".toCharArray(), "衣服".toCharArray())) { false })
            val maximum = Array(8) { words[it % 2].copyOf() }
            val context = CharArray(16) { '学' }
            repeat(10) { scorer.score(context, maximum) { false }?.fill(0f) }
            val beforeCpu = android.os.Process.getElapsedCpuTime()
            val timings = DoubleArray(100) {
                val before = System.nanoTime()
                checkNotNull(scorer.score(context, maximum) { false }).fill(0f)
                (System.nanoTime() - before) / 1_000_000.0
            }.sorted()
            val cpu = android.os.Process.getElapsedCpuTime() - beforeCpu
            val afterPss = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss
            instrumentation.sendStatus(0, Bundle().apply {
                putString("mini_production_init_ms", initialized.toString())
                putString("mini_production_p50_ms", timings[49].toString())
                putString("mini_production_p95_ms", timings[94].toString())
                putString("mini_production_100_cpu_ms", cpu.toString())
                putString("mini_production_pss_before_kib", beforePss.toString())
                putString("mini_production_pss_after_kib", afterPss.toString())
            })
            prefix.fill('\u0000'); words.forEach { it.fill('\u0000') }; result.fill(0f)
            maximum.forEach { it.fill('\u0000') }; context.fill('\u0000')
        }
    }

    @Test fun editorOnlyReportsSuccessfulCommitsAndInvalidatesOnCursorDeletionAndReopen() = onMain {
        val committed = mutableListOf<String?>()
        var invalidations = 0
        var succeeds = true
        val connection = object : BaseInputConnection(View(instrumentation.targetContext), true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = succeeds
        }
        val adapter = AndroidEditorConnection(0, 0, { committed += it }, { invalidations++ }) { connection }
        assertTrue(adapter.commitText("知识"))
        assertFalse(adapter.updateSelection(2, 2))
        assertEquals(0, invalidations)
        succeeds = false
        assertFalse(adapter.commitText("芝士"))
        assertEquals(listOf("知识", null), committed)
        assertTrue(adapter.updateSelection(0, 0))
        adapter.deleteBeforeCursor()
        adapter.reopenCommittedText("知识")
        assertEquals(3, invalidations)
    }

    @Test fun enabledImeSpaceCommitsItsVisibleHighlightedChoice() {
        val original = shell("settings get secure default_input_method").trim()
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        val settings = graph.settings
        val previous = Triple(settings.experimentalModelRanking, settings.learningEnabled, settings.incognitoMode)
        val options = settings.chineseInputOptions
        val language = settings.lastLanguage
        val pack = settings.lastLanguagePackKey
        var activity: InputFixtureActivity? = null
        try {
            onMain {
                settings.experimentalModelRanking = true; settings.learningEnabled = true; settings.incognitoMode = false
                settings.chineseInputOptions = ChineseInputOptions()
            }
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method"); shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("input_type", InputType.TYPE_CLASS_TEXT)
                .putExtra("ime_options", EditorInfo.IME_ACTION_DONE)) as InputFixtureActivity
            await { panelOrNull() != null }
            onMain {
                // This public synthetic fixture explicitly opts into the context policy.
                activity.editor.imeOptions = EditorInfo.IME_ACTION_DONE
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(activity.editor)
            }
            // The engine and the model warm asynchronously; the first request is allowed to time out.
            SystemClock.sleep(800)
            onMain {
                settings.lastLanguage = dev.zeroinput.engine.api.InputLanguage.CHINESE
                settings.lastLanguagePackKey = null
            }
            await { views(panel()).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "中" } }
            SystemClock.sleep(400)
            onMain {
                panel().onKeyboardAction(KeyboardAction.LiteralText("老师正在传授"))
                "zhishi".forEach { panel().onKeyboardAction(KeyboardAction.Text(it.toString())) }
            }
            SystemClock.sleep(600)
            onMain {
                panel().onKeyboardAction(KeyboardAction.Backspace)
                panel().onKeyboardAction(KeyboardAction.Text("i"))
            }
            await { visibleWords().isNotEmpty() }
            SystemClock.sleep(120)
            var chosen = ""
            onMain {
                // A learned personal row can precede the engine highlight on repeat runs.
                chosen = views(panel()).filterIsInstance<TextView>().single {
                    it.isShown && it.javaClass.simpleName == "CandidateItemView" && it.isSelected
                }.text.toString()
                panel().onKeyboardAction(KeyboardAction.Space)
            }
            await { activity.editor.text.toString() == "老师正在传授" + chosen }
            onMain { settings.experimentalModelRanking = false }
        } finally {
            activity?.let { onMain { it.finish() } }
            onMain {
                settings.experimentalModelRanking = previous.first; settings.learningEnabled = previous.second
                settings.incognitoMode = previous.third; settings.chineseInputOptions = options
                settings.lastLanguage = language; settings.lastLanguagePackKey = pack
            }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    private fun visibleWords(): List<String> = views(panel()).filterIsInstance<TextView>()
        .filter { it.isShown && it.javaClass.simpleName == "CandidateItemView" }.map { it.text.toString() }
    private fun panelOrNull() = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }
    private fun panel() = checkNotNull(panelOrNull())
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Model fixture did not reach expected state")
    }
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
