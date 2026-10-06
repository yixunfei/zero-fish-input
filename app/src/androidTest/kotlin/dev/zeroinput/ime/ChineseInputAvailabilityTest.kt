package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.InputEngineStatus
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.zeroinput.ime.ui.R as UiR

/** Real keyboard clicks and editor round trips; all text is a public fixture. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ChineseInputAvailabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun delayedNativePreparationKeepsFallbackSelectableThenAdoptsRime() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = graph.engineExecutor.submit {
            entered.countDown()
            check(release.await(45, TimeUnit.SECONDS)) { "Fixture preparation was not released" }
        }
        try {
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            withEditor(InputType.TYPE_CLASS_TEXT) { activity ->
                await { hasVisibleStatus(UiR.string.engine_preparing_basic) }
                typePinyin()
                await { candidate() != null }
                onMain {
                    assertTrue(hasVisibleStatus(UiR.string.engine_preparing_basic))
                    views(panel()).first { it.isShown && it.contentDescription ==
                        it.context.getString(UiR.string.keyboard_tools) }.performClick()
                    assertTrue(hasVisibleStatus(UiR.string.engine_preparing_basic))
                    views(panel()).first { it.isShown && it.contentDescription ==
                        it.context.getString(UiR.string.keyboard_return) }.performClick()
                    checkNotNull(candidate()).performClick()
                }
                await { activity.editor.text.toString() == "你好" }
                release.countDown()
                await { panel().renderedEngineStatus == InputEngineStatus.READY }
                onMain { assertFalse(hasVisibleStatus(UiR.string.engine_preparing_basic)) }
                typePinyin()
                await { candidate() != null }
                onMain { checkNotNull(candidate()).performClick() }
                await { activity.editor.text.toString() == "你好你好" }
            }
        } finally {
            release.countDown()
            blocker.get(60, TimeUnit.SECONDS)
        }
    }

    @Test fun noSuggestionsEditorConvertsWithCandidateAndSpaceButEnterCommitsRawInput() {
        withEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE) { activity ->
            await { panel().renderedEngineStatus == InputEngineStatus.READY }
            for (index in 1..3) {
                typePinyin()
                await { candidate() != null }
                onMain {
                    when (index) {
                        1 -> checkNotNull(candidate()).performClick()
                        2 -> panel().onKeyboardAction(KeyboardAction.Space)
                        3 -> panel().onKeyboardAction(KeyboardAction.Enter)
                    }
                }
                val expected = "你好".repeat(minOf(index, 2)) + if (index == 3) "nihao" else ""
                await { activity.editor.text.toString() == expected }
            }
            onMain { panel().onKeyboardAction(KeyboardAction.Enter) }
            await { activity.editor.text.toString() == "你好你好nihao\n" }
        }
    }

    @Test fun noSuggestionsEnglishEnterCommitsRawInputBeforeNewlineWithoutPredictions() {
        withEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE) { activity ->
            onMain { panel().onKeyboardAction(KeyboardAction.SwitchLanguage) }
            typeText("hel")
            onMain {
                assertFalse(views(panel()).any {
                    it.isShown && it.javaClass.simpleName == "CandidateItemView"
                })
                panel().onKeyboardAction(KeyboardAction.Space)
            }
            await { activity.editor.text.toString() == "hel " }
            typeText("hel")
            onMain { panel().onKeyboardAction(KeyboardAction.Enter) }
            await { activity.editor.text.toString() == "hel hel" }
            onMain { panel().onKeyboardAction(KeyboardAction.Enter) }
            await { activity.editor.text.toString() == "hel hel\n" }
        }
    }

    private fun withEditor(inputType: Int, action: (InputFixtureActivity) -> Unit) {
        val originalMethod = shell("settings get secure default_input_method").trim()
        val settings = graph.settings
        val options = settings.chineseInputOptions
        val language = settings.lastLanguage
        val pack = settings.lastLanguagePackKey
        var activity: InputFixtureActivity? = null
        try {
            onMain {
                settings.chineseInputOptions = ChineseInputOptions()
                settings.lastLanguagePackKey = null
            }
            val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("input_type", inputType)) as InputFixtureActivity
            await { panelOrNull() != null }
            onMain {
                if (views(panel()).filterIsInstance<TextView>().any {
                    it.contentDescription == it.context.getString(UiR.string.language_switch) && it.text.toString() == "En"
                }) panel().onKeyboardAction(KeyboardAction.SwitchLanguage)
            }
            action(activity)
        } finally {
            activity?.let { onMain { it.finish() } }
            if (originalMethod.isNotBlank() && originalMethod != "null") shell("ime set $originalMethod")
            onMain {
                settings.chineseInputOptions = options
                settings.lastLanguage = language
                settings.lastLanguagePackKey = pack
            }
        }
    }

    private fun typePinyin() = typeText("nihao")

    private fun typeText(text: String) {
        for (character in text) {
            onMain {
                views(panel()).filterIsInstance<TextView>().first {
                    it.isShown && it.text.toString() == character.toString()
                }.performClick()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun candidate(): TextView? = views(panel()).filterIsInstance<TextView>().firstOrNull {
        it.isShown && it.javaClass.simpleName == "CandidateItemView" && it.text.toString() == "你好"
    }
    private fun hasVisibleStatus(resource: Int): Boolean = views(panel()).filterIsInstance<TextView>().any {
        it.isShown && it.width > 0 && it.height > 0 && it.text.toString() == it.context.getString(resource)
    }
    private fun panelOrNull() = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }
    private fun panel() = checkNotNull(panelOrNull())
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Chinese input fixture did not reach the expected state")
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
