package dev.zeroinput.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.input.AndroidEditorConnection
import dev.zeroinput.ime.model.ModelRankingCoordinator
import dev.zeroinput.model.RobertaMiniScorer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real native candidates, production scorer and platform editor; no personal-store side effects. */
@RunWith(AndroidJUnit4::class)
class ModelNativeRankingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun miniPromotionRoutesTapSpaceAndPunctuationWhileEnterCommitsRawInput() {
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        for (command in listOf(InputCommand.SelectCandidate(0), InputCommand.Space, InputCommand.Enter, InputCommand.LiteralText("。"))) {
            val engine = graph.engineExecutor.submit<InputEngine> {
                checkNotNull(graph.rime.createNativeOrNull(ChineseInputOptions()))
            }.get(60, TimeUnit.SECONDS)
            var controller: InputSessionController? = null
            lateinit var editor: EditText
            val coordinator = ModelRankingCoordinator(
                { RobertaMiniScorer(instrumentation.targetContext) }, { Handler(Looper.getMainLooper()).post(it) },
                { controller }, { controller?.state?.privacy?.personalizationAllowed == true },
            )
            try {
                onMain {
                    editor = EditText(instrumentation.targetContext).apply { inputType = InputType.TYPE_CLASS_TEXT }
                    val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                    val adapter = AndroidEditorConnection(0, 0, coordinator::committed, coordinator::invalidate) { connection }
                    controller = InputSessionController(adapter, { engine }, EmptyStore,
                        onStateChanged = { coordinator.refresh(true) })
                    val active = checkNotNull(controller)
                    active.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
                    active.handle(InputCommand.LiteralText("老师正在传授"))
                    coordinator.typing()
                    "zhishi".forEach { active.handle(InputCommand.Text(it.toString())) }
                }
                SystemClock.sleep(300)
                onMain {
                    val active = checkNotNull(controller)
                    if (!active.state.modelRanked) {
                        active.handle(InputCommand.Backspace)
                        active.handle(InputCommand.Text("i"))
                    }
                }
                await { controller?.state?.modelRanked == true }
                onMain {
                    val active = checkNotNull(controller)
                    assertEquals("知识", active.state.snapshot.candidates.first().text)
                    active.handle(command)
                    val expected = if (command == InputCommand.Enter) "老师正在传授zhishi"
                        else "老师正在传授知识" + if (command is InputCommand.LiteralText) "。" else ""
                    assertEquals(expected, editor.text.toString())
                }
            } finally {
                onMain { coordinator.close(); controller?.close() }
            }
        }
    }

    private object EmptyStore : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Native model fixture did not promote the expected word")
    }
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
