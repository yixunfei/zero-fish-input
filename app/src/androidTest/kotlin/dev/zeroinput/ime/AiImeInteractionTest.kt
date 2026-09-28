package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
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

    private fun withEditor(test: (InputFixtureActivity) -> Unit) {
        val originalMethod = shell("settings get secure default_input_method").trim()
        val originalConfig = graph.aiConfiguration.read()
        val learning = graph.settings.learningEnabled
        val incognito = graph.settings.incognitoMode
        var activity: InputFixtureActivity? = null
        try {
            configure(AiConfiguration())
            onMain { graph.settings.learningEnabled = true; graph.settings.incognitoMode = false }
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
            test(fixture)
        } finally {
            activity?.let { onMain { it.finish() } }
            onMain { graph.settings.learningEnabled = learning; graph.settings.incognitoMode = incognito }
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
