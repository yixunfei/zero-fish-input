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
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.ime.personalization.QueuedPersonalizationStore
import dev.zeroinput.ime.testing.DeviceTouch
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.testing.MiniResourceRule
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.userdata.UserLexiconRepository
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
    @get:org.junit.Rule val miniResources = MiniResourceRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun repeatedRealClicksPromoteAndPreserveNativeRouteAcrossInputCodes() =
        exerciseLearning("ceshi", "cs", "测试")

    @Test fun repeatedNonDefaultInitialCandidateClicksPromoteXianZhi() =
        exerciseLearning("xz", "xz", "限制")

    @Test fun oneNonDefaultClickPromotesAtTheDefaultThreshold() =
        exerciseLearning("xz", "xz", "限制", promotionThreshold = 1)

    @Test fun screenTouchesPromoteNonDefaultInitialCandidateWithModelRankingEnabled() =
        exerciseLearning("xz", "xz", "限制", modelRanking = true, screenTouches = true)

    @Test fun noSuggestionsChatEditorLearnsExplicitChoicesAcrossRestart() =
        exerciseLearning("xz", "xz", "限制", screenTouches = true,
            editorInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)

    @Test fun applicationAutocompleteEditorLearnsExplicitChoicesAcrossRestart() =
        exerciseLearning("xz", "xz", "限制", editorInputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)

    @Test fun filterEditorLearnsExplicitChoicesAcrossRestart() =
        exerciseLearning("xz", "xz", "限制",
            editorInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER)

    @Test fun combinedSuggestionAndFilterFlagsKeepExplicitLearning() =
        exerciseLearning("xz", "xz", "限制", editorInputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)

    private fun exerciseLearning(learnCode: String, probeCode: String, word: String, promotionThreshold: Int = 3,
        modelRanking: Boolean = false, screenTouches: Boolean = false, editorInputType: Int = InputType.TYPE_CLASS_TEXT) {
        val original = shell("settings get secure default_input_method").trim()
        val previous = graph.settings.privacyConfiguration()
        val pack = graph.settings.lastLanguagePackKey
        val options = graph.settings.chineseInputOptions
        val model = graph.settings.experimentalModelRanking
        val language = graph.settings.lastLanguage
        val threshold = graph.settings.personalPromotionThreshold
        var activity: InputFixtureActivity? = null
        try {
            graph.engineExecutor.submit {}.get(60, TimeUnit.SECONDS)
            onMain {
                graph.settings.learningEnabled = true
                graph.settings.incognitoMode = false
                graph.settings.experimentalModelRanking = modelRanking
                graph.settings.chineseInputOptions = ChineseInputOptions()
                graph.settings.lastLanguagePackKey = null
                graph.settings.personalPromotionThreshold = promotionThreshold
            }
            (graph.personalization as QueuedPersonalizationStore).clearAndAwait()
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method")
            shell("ime set $method")
            val editor = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("ordinary_fixture", true)
                .putExtra("allow_multiline_ime_action", true)
                .putExtra("input_type", editorInputType)
                .putExtra("ime_options", EditorInfo.IME_ACTION_DONE)) as InputFixtureActivity
            activity = editor
            await { panelOrNull() != null }
            instrumentation.waitForIdleSync()
            onMain {
                if (views(panel()).filterIsInstance<TextView>().none { it.isShown && it.text.toString() == "中" }) {
                    panel().onKeyboardAction(KeyboardAction.SwitchLanguage)
                }
            }
            await { views(panel()).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "中" } }
            await { panel().renderedEngineStatus == dev.zeroinput.ime.ui.InputEngineStatus.READY }
            repeat(promotionThreshold) { round ->
                type(learnCode, screenTouches)
                if (round == 0 && learnCode == "xz") assertNotEquals(word, onMain { words().firstOrNull() })
                choose(word, screenTouches)
                await { editor.editor.text.toString() == word.repeat(round + 1) }
                awaitFrequency(word, round + 1)
            }
            val reloaded = graph.engineExecutor.submit<Int> {
                UserLexiconRepository(instrumentation.targetContext).frequenciesFor(listOf(word), InputLanguage.CHINESE)[word] ?: 0
            }.get(10, TimeUnit.SECONDS)
            assertEquals(promotionThreshold, reloaded)
            onMain {
                editor.editor.imeOptions = EditorInfo.IME_ACTION_SEARCH
                editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
            }
            // Main-thread idleness or the old READY indicator does not acknowledge
            // restartInput's system IPC. Wait for the new editor's action instead.
            await { views(panel()).any { it.isShown && it.javaClass.simpleName == "KeyboardKeyView" &&
                it.contentDescription == it.context.getString(dev.zeroinput.ime.ui.R.string.key_search) } }
            await { panel().renderedEngineStatus == dev.zeroinput.ime.ui.InputEngineStatus.READY }
            type(probeCode, screenTouches)
            await { words().firstOrNull() == word }
            onMain { panel().onKeyboardAction(KeyboardAction.Space) }
            await { editor.editor.text.toString() == word.repeat(promotionThreshold + 1) }
            type(probeCode, screenTouches)
            await { words().firstOrNull() == word }
            // Observe beyond the scorer's 80 ms result deadline after a new
            // composition with committed context; a transient first place is insufficient.
            SystemClock.sleep(200)
            onMain { assertEquals(word, words().firstOrNull()) }
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
                graph.settings.personalPromotionThreshold = threshold
            }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    private fun type(value: String, screenTouches: Boolean = false) {
        value.forEach { letter ->
            if (screenTouches) DeviceTouch.tapVisible {
                views(panel()).filterIsInstance<TextView>().first { it.isShown &&
                    it.javaClass.simpleName == "KeyboardKeyView" && it.text.toString() == letter.toString() }
            } else onMain { panel().onKeyboardAction(KeyboardAction.Text(letter.toString())) }
        }
    }

    private fun awaitFrequency(word: String, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val count = graph.engineExecutor.submit<Int> {
                graph.userLexicon.frequenciesFor(listOf(word), InputLanguage.CHINESE)[word] ?: 0
            }.get(10, TimeUnit.SECONDS)
            if (count == expected) return
            SystemClock.sleep(50)
        }
        fail("Accepted candidate usage did not reach the encrypted lexicon")
    }

    private fun choose(value: String, screenTouches: Boolean = false) {
        repeat(24) {
            if (onMain { value in words() }) {
                select(value, screenTouches)
                return
            }
            onMain { panel().onCandidatePageChanged(PageDirection.NEXT) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
        await { value in words() }
        select(value, screenTouches)
    }

    private fun select(value: String, screenTouches: Boolean) {
        if (screenTouches) DeviceTouch.tap { candidates().first { it.text.toString() == value } }
        else onMain { candidates().first { it.text.toString() == value }.performClick() }
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
