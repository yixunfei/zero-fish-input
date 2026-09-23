package dev.zeroinput.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.model.ModelRankingCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ModelSessionPrivacyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun privateEditorsAndDisabledModeNeverLoadTheScorer() = onMain {
        val cases = listOf(
            Triple(InputType.TYPE_NULL, 0, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, PrivacyConfiguration()),
            Triple(InputType.TYPE_CLASS_TEXT, 0, PrivacyConfiguration(incognitoMode = true)),
            Triple(InputType.TYPE_CLASS_TEXT, 0, PrivacyConfiguration(learningEnabled = false)),
        )
        for ((type, flags, privacy) in cases) Harness().use { harness ->
            harness.start(type, flags, privacy)
            harness.typeWithContext()
            assertEquals(0, harness.created.get())
        }
        Harness().use { harness ->
            harness.enabled = false
            harness.start()
            harness.typeWithContext()
            assertEquals(0, harness.created.get())
        }
    }

    @Test fun contextInvalidationAndCandidateTouchPreventPromotionUntilNewContext() {
        lateinit var harness: Harness
        onMain { harness = Harness(); harness.start(); harness.typeWithContext() }
        try {
            await { harness.controller.state.modelRanked }
            onMain {
                harness.coordinator.invalidate()
                harness.controller.clearModelRanking()
                harness.coordinator.typing()
                harness.coordinator.refresh(true)
                assertFalse(harness.controller.state.modelRanked)
                harness.coordinator.committed("老师正在传授")
                harness.coordinator.interaction()
                harness.controller.refreshPersonalization()
                harness.coordinator.refresh(true)
            }
            SystemClock.sleep(100)
            onMain { assertFalse(harness.controller.state.modelRanked); harness.typeWithContext() }
            await { harness.controller.state.modelRanked }
            onMain {
                harness.enabled = false
                harness.coordinator.invalidate()
                harness.controller.updatePrivacy(PrivacyConfiguration(incognitoMode = true))
                assertFalse(harness.controller.state.modelRanked)
            }
        } finally { onMain { harness.close() } }
    }

    private class Harness : AutoCloseable {
        val created = AtomicInteger()
        var enabled = true
        private var active: InputSessionController? = null
        val controller get() = checkNotNull(active)
        val coordinator = ModelRankingCoordinator(
            createScorer = {
                created.incrementAndGet()
                object : CandidateScorer {
                    override fun score(context: CharArray, words: Array<CharArray>, cancelled: () -> Boolean) = floatArrayOf(-4f, -1f)
                    override fun close() = Unit
                }
            }, post = { Handler(Looper.getMainLooper()).post(it) }, currentController = { active },
            allowed = { enabled && active?.state?.privacy?.personalizationAllowed == true },
        )

        fun start(type: Int = InputType.TYPE_CLASS_TEXT, flags: Int = 0, privacy: PrivacyConfiguration = PrivacyConfiguration()) {
            active = InputSessionController(Editor(), { Engine() }, Store, onStateChanged = { coordinator.refresh(true) })
            controller.start(EditorInfo().apply { inputType = type; imeOptions = flags }, InputLanguage.CHINESE, privacy)
        }
        fun typeWithContext() {
            coordinator.committed("老师正在传授")
            coordinator.typing()
            controller.handle(InputCommand.Text("i"))
            coordinator.refresh(true)
        }
        override fun close() { coordinator.close(); active?.close(); active = null }
    }

    private class Engine : InputEngine {
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE))
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = snapshot
        override fun handle(key: EngineKey): EngineUpdate {
            snapshot = EngineSnapshot("zhishi", "zhi shi", listOf(
                Candidate("0", "只是", input = "zhishi"), Candidate("1", "知识", input = "zhishi")))
            return EngineUpdate(snapshot)
        }
        override fun selectCandidate(index: Int) = EngineUpdate(snapshot)
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() = Unit
    }
    private class Editor : EditorConnection {
        override fun commitText(text: String) = true
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }
    private object Store : PersonalizationStore {
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
        fail("Model privacy fixture did not reach expected state")
    }
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
