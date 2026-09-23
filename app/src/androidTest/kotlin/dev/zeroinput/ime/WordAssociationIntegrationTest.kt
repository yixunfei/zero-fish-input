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
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.settings.ChineseEngineChoice
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real IME/editor round trips with public constructed phrases only. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class WordAssociationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun englishChainsWithOneSeparatorAndSpaceOrEnterNeverAcceptsPrediction() =
        withEditor(InputLanguage.ENGLISH) { activity ->
            type("thank")
            onMain { panel().onKeyboardAction(KeyboardAction.Space) }
            await { activity.editor.text.toString() == "thank " && "you" in words() }
            choose("you")
            await { activity.editor.text.toString() == "thank you" && "very" in words() }
            choose("very")
            await { activity.editor.text.toString() == "thank you very" && "much" in words() }
            onMain { panel().onKeyboardAction(KeyboardAction.Space) }
            await { activity.editor.text.toString() == "thank you very " && words().isEmpty() }
            type("good")
            choose("good")
            await { "morning" in words() }
            onMain { panel().onKeyboardAction(KeyboardAction.Enter) }
            await { activity.editorActions.contains(EditorInfo.IME_ACTION_DONE) && words().isEmpty() }
            onMain { assertEquals("thank you very good", activity.editor.text.toString()) }
        }

    @Test fun chineseAssociationsClearOnCursorSettingPrivacyAndEditorRestart() =
        withEditor(InputLanguage.CHINESE) { activity ->
            type("xiexie")
            choose("谢谢")
            await { activity.editor.text.toString() == "谢谢" && "你" in words() }
            choose("你")
            await { activity.editor.text.toString() == "谢谢你" && "的帮助" in words() }
            onMain { activity.editor.setSelection(0) }
            await { words().isEmpty() }
            onMain { activity.editor.setSelection(activity.editor.length()) }
            type("nihao")
            choose("你好")
            await { "世界" in words() }
            onMain { graph.settings.wordAssociationsEnabled = false }
            await { words().isEmpty() }
            onMain { graph.settings.wordAssociationsEnabled = true }
            onMain { assertTrue(words().isEmpty()) }
            type("nihao")
            choose("你好")
            await { "世界" in words() }
            onMain { graph.settings.incognitoMode = true }
            await { words().isEmpty() }
            type("nihao")
            choose("你好")
            await { words().isEmpty() }
            onMain {
                graph.settings.incognitoMode = false
                activity.editor.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                activity.getSystemService(InputMethodManager::class.java).restartInput(activity.editor)
            }
            // restartInput is a platform IPC; an already empty strip is not its acknowledgement.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            await { words().isEmpty() }
            type("nihao")
            choose("你好")
            await { words().isEmpty() }
        }

    @Test fun traditionalSuggestionsMatchTheActiveChineseScript() =
        withEditor(InputLanguage.CHINESE, ChineseScript.TRADITIONAL) { activity ->
            type("shengri")
            choose("生日")
            await { "快樂" in words() }
            onMain { assertFalse("快乐" in words()) }
            choose("快樂")
            await { activity.editor.text.toString() == "生日快樂" }
        }

    @Test fun expandedChinesePairsWorkAndEmbeddedSingleCharacterAnchorsStayHidden() =
        withEditor(InputLanguage.CHINESE) { activity ->
            type("lianxi")
            choose("联系")
            await { "方式" in words() }
            choose("方式")
            await { activity.editor.text.toString() == "联系方式" }
            type("ziwo")
            choose("自我")
            await { activity.editor.text.toString() == "联系方式自我" && words().isEmpty() }
        }

    @Test fun expandedEnglishHelpRequestChainsAcrossThreeSuccessfulSelections() =
        withEditor(InputLanguage.ENGLISH) { activity ->
            type("could")
            onMain { panel().onKeyboardAction(KeyboardAction.Space) }
            await { "you" in words() }
            choose("you")
            await { "please" in words() }
            choose("please")
            await { "help" in words() }
            choose("help")
            await { activity.editor.text.toString() == "could you please help" }
        }

    private fun withEditor(language: InputLanguage, script: ChineseScript = ChineseScript.SIMPLIFIED,
        action: (InputFixtureActivity) -> Unit) {
        val original = shell("settings get secure default_input_method").trim()
        val settings = graph.settings
        val privacy = settings.privacyConfiguration()
        val enabled = settings.wordAssociationsEnabled
        val model = settings.experimentalModelRanking
        val previousLanguage = settings.lastLanguage
        val pack = settings.lastLanguagePackKey
        val options = settings.chineseInputOptions
        val engine = settings.chineseEngine
        var activity: InputFixtureActivity? = null
        try {
            graph.engineExecutor.submit {}.get(60, TimeUnit.SECONDS)
            onMain {
                settings.learningEnabled = true; settings.incognitoMode = false
                settings.wordAssociationsEnabled = true; settings.experimentalModelRanking = false
                settings.chineseInputOptions = ChineseInputOptions(script = script); settings.chineseEngine = ChineseEngineChoice.RIME
                settings.lastLanguagePackKey = null
            }
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method"); shell("ime set $method")
            val editor = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("input_type", InputType.TYPE_CLASS_TEXT)
                .putExtra("ime_options", EditorInfo.IME_ACTION_DONE)) as InputFixtureActivity
            activity = editor
            await { panelOrNull() != null }
            onMain {
                // Opt this public fixture into the normal-editor context policy.
                editor.editor.imeOptions = EditorInfo.IME_ACTION_DONE
                editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
            }
            SystemClock.sleep(400)
            val label = if (language == InputLanguage.ENGLISH) "En" else "中"
            onMain {
                // The keyboard action also selects Android's subtype, which restartInput restores.
                if (views(panel()).filterIsInstance<TextView>().none { it.isShown && it.text.toString() == label }) {
                    panel().onKeyboardAction(KeyboardAction.SwitchLanguage)
                }
            }
            await { views(panel()).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == label } }
            // Permit the lifecycle-owned prepared-engine handoff to finish before the fixture starts.
            SystemClock.sleep(400)
            action(editor)
        } finally {
            activity?.let { onMain { it.finish() } }
            onMain {
                settings.learningEnabled = privacy.learningEnabled; settings.incognitoMode = privacy.incognitoMode
                settings.wordAssociationsEnabled = enabled; settings.experimentalModelRanking = model
                settings.lastLanguage = previousLanguage; settings.lastLanguagePackKey = pack
                settings.chineseInputOptions = options; settings.chineseEngine = engine
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
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Association fixture did not reach expected state")
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
