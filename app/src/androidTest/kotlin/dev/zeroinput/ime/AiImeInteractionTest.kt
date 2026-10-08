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
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.userdata.AiConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class AiImeInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun realImeShowsAiOnFirstScreenWithoutOpeningOrScrollingTools() = withEditor { editor ->
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_open) }
        await { panel().isAiOpen && panel().isAiEditing }
        onMain { assertTrue(editor.editor.text.isEmpty()) }
        tap { labelled(UiR.string.keyboard_return) }
        await { !panel().isAiOpen }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.keyboard_tools) }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_open) }
        await { panel().isAiOpen && panel().isAiEditing }
    }

    @Test fun realImeToolsOpenAiAndShowDisabledRequestWithoutChangingEditor() = withEditor { editor ->
        tap { labelled(UiR.string.keyboard_tools) }
        await { labelledOrNull(UiR.string.ai_open) != null }
        tap { labelled(UiR.string.ai_open) }
        await { panel().isAiEditing }
        for (letter in listOf("n", "i", "h", "a", "o")) tap {
            views(panel()).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == letter }
        }
        await { draft().text.isNotEmpty() }
        onMain { assertTrue("AI typing must not compose in the host editor", editor.editor.text.isEmpty()) }
        tap { textButton(UiR.string.ai_submit) }
        await { !panel().isAiEditing }
        onMain {
            assertNotNull(textButton(UiR.string.ai_policy_unavailable))
            assertFalse(textButton(UiR.string.ai_insert).isEnabled)
            assertTrue(editor.editor.text.isEmpty())
        }
        tap { labelled(UiR.string.keyboard_return) }
        await { !panel().isAiOpen }
        tap { labelled(UiR.string.keyboard_tools) }
        tap { labelled(UiR.string.ai_open) }
        onMain { assertTrue("Closing the workbench clears its draft", draft().text.isEmpty()) }
        onMain {
            editor.editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
        }
        await { !panel().isAiOpen }
        tap { labelled(UiR.string.keyboard_tools) }
        onMain { assertNull(labelledOrNull(UiR.string.ai_open)) }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_entry_unavailable) }
        onMain { assertFalse(panel().isAiOpen); assertTrue(editor.editor.text.isEmpty()) }
    }

    @Test fun selectedContentRequiresKeyboardClaimAndClearsOnEditorChange() = withEditor { editor ->
        onMain {
            graph.aiContentInbox.put(dev.zeroinput.ime.ai.AiImportedContent("public imported context".toCharArray(),
                listOf(dev.zeroinput.ai.api.AiAttachment("text/plain", "fixture".toByteArray(), "fixture.txt"))))
        }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_open) }
        await { panel().isAiOpen }
        onMain { assertTrue(draft().text.isEmpty()); assertTrue(editor.editor.text.isEmpty()) }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_more) }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible {
            WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<TextView>().first {
                it.isShown && it.text == it.context.getString(UiR.string.ai_import_content)
            }
        }
        onMain {
            assertTrue(draft().text.isEmpty())
            views(panel()).filterIsInstance<TextView>().first { it.text.toString().startsWith(
                it.context.getString(UiR.string.ai_context_title)) }.performClick()
        }
        await { views(panel()).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "public imported context" } }
        onMain { assertFalse(graph.aiContentInbox.available()); assertTrue(editor.editor.text.isEmpty()) }
        onMain {
            editor.editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
        }
        await { !panel().isAiOpen }
        onMain { assertTrue(editor.editor.text.isEmpty()) }
    }

    @Test fun noSuggestionsChatOpensIndependentChineseDraftButNoLearningStillBlocksAi() = withEditor { editor ->
        onMain {
            editor.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editor.editor.imeOptions = 0
            editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
        }
        await { labelledOrNull(UiR.string.ai_open)?.isEnabled == true }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_open) }
        await { panel().isAiEditing }
        onMain {
            for (character in "nihao") panel().onAiDraftChanged(dev.zeroinput.ime.ui.KeyboardAction.Text(character.toString()))
            panel().onAiDraftChanged(dev.zeroinput.ime.ui.KeyboardAction.Space)
        }
        await { draft().text.toString() == "你好" }
        onMain {
            for (character in "zhongguo") panel().onAiDraftChanged(dev.zeroinput.ime.ui.KeyboardAction.Text(character.toString()))
            panel().onAiDraftChanged(dev.zeroinput.ime.ui.KeyboardAction.Space)
        }
        await { draft().text.toString() == "你好中国" }
        onMain {
            assertTrue(editor.editor.text.isEmpty())
            editor.editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            editor.getSystemService(InputMethodManager::class.java).restartInput(editor.editor)
        }
        await { !panel().isAiOpen && labelledOrNull(UiR.string.ai_entry_unavailable) != null }
        dev.zeroinput.ime.testing.DeviceTouch.tapVisible { labelled(UiR.string.ai_entry_unavailable) }
        onMain { assertFalse(panel().isAiOpen); assertTrue(editor.editor.text.isEmpty()) }
    }

    private fun withEditor(test: (InputFixtureActivity) -> Unit) {
        val originalMethod = shell("settings get secure default_input_method").trim()
        val originalConfig = graph.aiConfiguration.read()
        val learning = graph.settings.learningEnabled
        val incognito = graph.settings.incognitoMode
        val language = graph.settings.lastLanguage
        val pack = graph.settings.lastLanguagePackKey
        var activity: InputFixtureActivity? = null
        try {
            configure(AiConfiguration())
            onMain {
                graph.settings.learningEnabled = true
                graph.settings.incognitoMode = false
                graph.settings.lastLanguage = dev.zeroinput.engine.api.InputLanguage.CHINESE
                graph.settings.lastLanguagePackKey = null
            }
            shell("ime enable dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService")
            shell("ime set dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
            val fixture = activity
            await { panels().isNotEmpty() }
            onMain {
                fixture.editor.imeOptions = 0
                fixture.getSystemService(InputMethodManager::class.java).restartInput(fixture.editor)
            }
            await { panels().isNotEmpty() && views(panel()).any {
                it.contentDescription == it.context.getString(UiR.string.ai_open) && it.visibility == View.VISIBLE
            } }
            onMain {
                if (views(panel()).filterIsInstance<TextView>().any {
                    it.contentDescription == it.context.getString(UiR.string.language_switch) && it.text.toString() == "En"
                }) panel().onKeyboardAction(dev.zeroinput.ime.ui.KeyboardAction.SwitchLanguage)
            }
            test(fixture)
        } finally {
            activity?.let { onMain { it.finish() } }
            onMain {
                graph.settings.learningEnabled = learning
                graph.settings.incognitoMode = incognito
                graph.settings.lastLanguage = language
                graph.settings.lastLanguagePackKey = pack
            }
            configure(originalConfig)
            if (originalMethod.isNotBlank() && originalMethod != "null") shell("ime set $originalMethod")
        }
    }

    private fun configure(value: AiConfiguration) {
        val done = CountDownLatch(1)
        var saved = false
        graph.updateAiConfiguration(value) { saved = it; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(saved)
    }
    private fun panels() = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<ZeroInputView>().filter { it.isShown }
    private fun panel() = panels().single()
    private fun labelledOrNull(label: Int) = panels().flatMap(::views).firstOrNull {
        it.isShown && it.contentDescription == it.context.getString(label)
    }
    private fun labelled(label: Int) = checkNotNull(labelledOrNull(label))
    private fun textButton(label: Int) = views(panel()).filterIsInstance<TextView>().first {
        it.isShown && it.text == it.context.getString(label)
    }
    private fun draft() = views(panel()).filterIsInstance<TextView>().single {
        it.isShown && it.hint == it.context.getString(UiR.string.ai_input_hint)
    }
    private fun tap(find: () -> View) = dev.zeroinput.ime.testing.DeviceTouch.tap(find)
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("AI editor fixture did not reach the expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
