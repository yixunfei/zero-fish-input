package dev.zeroinput.ime.ai

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.testing.DeviceTouch
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConversationRepository
import dev.zeroinput.userdata.AiProviderProfile
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class AiWorkbenchFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ready = InputSessionState(privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE))

    @Test fun allActionsStreamAndRequireExplicitInsertion() = withFixture { f ->
        for ((label, action) in listOf(UiR.string.ai_action_ask to AiAction.ASK, UiR.string.ai_action_plan to AiAction.PLAN,
            UiR.string.ai_action_polish to AiAction.POLISH, UiR.string.ai_action_rewrite to AiAction.REWRITE,
            UiR.string.ai_action_translate to AiAction.TRANSLATE)) {
            DeviceTouch.tap { f.labelled(UiR.string.ai_open) }
            onMain { f.panel.renderAiDraft("public question", ready) }
            DeviceTouch.tap { f.button(label) }
            DeviceTouch.tap { f.button(UiR.string.ai_submit) }
            onMain {
                assertEquals(action, f.provider.request?.action)
                assertTrue(f.inserted.isEmpty())
                assertFalse(f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled)
                f.provider.emit(AiStreamEvent.Delta("public "))
                f.provider.emit(AiStreamEvent.Completed("public answer"))
            }
            await { f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled }
            onMain { assertTrue("Completion must not insert automatically", f.inserted.isEmpty()) }
            DeviceTouch.tap { f.panel.findViewById<TextView>(UiR.id.ai_insert_result) }
            onMain {
                assertEquals(listOf("public answer"), f.inserted)
                assertNull(f.controller.consumeResult())
                f.inserted.clear()
                assertFalse(f.panel.isAiOpen)
            }
        }
    }

    @Test fun stoppedAndPrivacyRevokedResponsesNeverBecomeInsertable() = withFixture { f ->
        DeviceTouch.tap { f.labelled(UiR.string.ai_open) }
        onMain { f.panel.renderAiDraft("public question", ready) }
        DeviceTouch.tap { f.button(UiR.string.ai_submit) }
        val cancelled = onMain { checkNotNull(f.provider.listener) }
        DeviceTouch.tap { f.button(UiR.string.ai_cancel) }
        onMain { cancelled(AiStreamEvent.Completed("late public response")) }
        instrumentation.waitForIdleSync()
        onMain { assertFalse(f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled); assertNull(f.controller.consumeResult()) }
        DeviceTouch.tap { f.button(UiR.string.ai_submit) }
        val oldEditor = onMain { checkNotNull(f.provider.listener) }
        onMain {
            f.allowed = false
            f.panel.renderSession(InputSessionState(privacy = SessionPrivacy(false, true, false, PrivacyReason.INCOGNITO_MODE)))
            oldEditor(AiStreamEvent.Completed("obsolete public response"))
        }
        instrumentation.waitForIdleSync()
        onMain { assertFalse(f.panel.isAiOpen); assertNull(f.controller.consumeResult()); assertTrue(f.inserted.isEmpty()) }
    }

    @Test fun multipleResponseSectionsCanBeSelectedBeforeInsertion() = withFixture { f ->
        DeviceTouch.tap { f.labelled(UiR.string.ai_open) }
        onMain { f.panel.renderAiDraft("public question", ready) }
        DeviceTouch.tap { f.button(UiR.string.ai_submit) }
        onMain { f.provider.emit(AiStreamEvent.Completed("first section\n\nsecond section\n\nthird section")) }
        await { f.panel.findViewById<View>(UiR.id.ai_result_sections_toggle).isShown }
        onMain { f.panel.findViewById<View>(UiR.id.ai_result_sections_toggle).performClick() }
        await { visible(f.panel).filterIsInstance<MaterialCheckBox>().size == 4 }
        onMain {
            val sections = visible(f.panel).filterIsInstance<MaterialCheckBox>()
            assertEquals("Select-all plus three answer sections", 4, sections.size)
            assertFalse(f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled)
            sections[2].performClick()
            sections[3].performClick()
            assertTrue(f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled)
            f.panel.findViewById<TextView>(UiR.id.ai_insert_result).performClick()
            assertEquals(listOf("second section\n\nthird section"), f.inserted)
        }
    }

    @Test fun aiCandidatesStayInTheDraftAndUseTheirOwnSelectionRoute() = withFixture { f ->
        val aiSelections = mutableListOf<String>()
        val hostSelections = mutableListOf<String>()
        onMain {
            f.panel.onAiCandidateSelected = { _, id -> aiSelections += id }
            f.panel.onCandidateSelected = { _, id -> hostSelections += id }
        }
        DeviceTouch.tap { f.labelled(UiR.string.ai_open) }
        onMain {
            f.panel.renderAiDraft("public question", ready.copy(snapshot = EngineSnapshot(
                rawInput = "ni", candidates = listOf(Candidate("ai-candidate", "你")), hasNextPage = true,
            )))
        }
        val candidate = onMain {
            visible(f.panel).filterIsInstance<TextView>().single {
                it.contentDescription == f.panel.context.getString(UiR.string.candidate_description, "你")
            }
        }
        DeviceTouch.tap { candidate }
        onMain {
            assertEquals(listOf("ai-candidate"), aiSelections)
            assertTrue(hostSelections.isEmpty())
        }
    }

    @Test fun conversationTranscriptStartsCollapsedAndCanBeOpened() = withFixture { f ->
        DeviceTouch.tap { f.labelled(UiR.string.ai_open) }
        onMain {
            val conversation = AiConversation(id = "fixture", title = "Public fixture", messages = listOf(
                AiMessage(AiRole.USER, "earlier question"),
                AiMessage(AiRole.ASSISTANT, "earlier answer"),
                AiMessage(AiRole.USER, "current question"),
                AiMessage(AiRole.ASSISTANT, "current answer"),
            ))
            f.panel.renderAiHistoryStatus(saving = true, loading = false, failed = false)
            f.panel.renderAiConversations(listOf(AiConversationSummary(conversation.id, conversation.title, 0)))
            f.button(UiR.string.ai_conversations_title).performClick()
            visible(f.panel).filterIsInstance<TextView>().single { it.text == "Public fixture" }.performClick()
            f.panel.renderAiConversation(conversation)
            assertTrue(visible(f.panel).none { it is TextView && it.text.toString().contains("earlier question") })
            f.button(UiR.string.ai_show_history).performClick()
            assertTrue(visible(f.panel).any { it is TextView && it.text.toString().contains("earlier question") })
            f.button(UiR.string.ai_hide_history).performClick()
            assertTrue(visible(f.panel).none { it is TextView && it.text.toString().contains("earlier question") })
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        val fixture = onMain { Fixture(activity) }
        try { test(fixture) } finally { onMain { fixture.close(); activity.finish() } }
    }

    private inner class Fixture(activity: KeyboardPreviewFixtureActivity) : AutoCloseable {
        val panel = activity.keyboard
        val provider = PublicProvider()
        val inserted = mutableListOf<String>()
        var allowed = true
        private val profile = AiProviderProfile(
            id = "fixture",
            name = "Fixture",
            endpoint = "https://provider.example/v1/chat/completions",
            apiKey = "public-fixture",
            models = listOf("fixture-model"),
            selectedModel = "fixture-model",
        )
        private val worker = Executors.newSingleThreadExecutor()
        private val handler = Handler(Looper.getMainLooper())
        val controller = AiWorkbenchController(AiCoordinator(provider), AiConversationRepository(PublicMemoryStore()), worker,
            {
                AiConfiguration(
                    enabled = true,
                    networkAllowed = true,
                    providers = listOf(profile),
                    selectedProviderId = profile.id,
                )
            }, AiDataGeneration(),
            { handler.post(it) }, { allowed && panel.isAiOpen }, panel::renderAi,
            panel::renderAiConversations, panel::renderAiConversation, editorPrivacy = { ready.privacy })

        init {
            panel.renderSession(ready)
            panel.onAiSubmit = controller::submit
            panel.onAiCancel = controller::stop
            panel.onAiInsert = { displayed, thinking, sections ->
                controller.consumeResult(thinking, displayed, sections)?.let(inserted::add)
                panel.returnToKeyboard()
            }
            panel.onAiVisibilityChanged = { if (!it) controller.invalidate() }
        }
        fun button(label: Int, vararg args: Any) = visible(panel).filterIsInstance<TextView>().first {
            it.text == it.context.getString(label, *args)
        }
        fun labelled(label: Int) = visible(panel).first { it.contentDescription == it.context.getString(label) }
        override fun close() { controller.invalidate(); worker.shutdownNow(); panel.release() }
    }

    private class PublicProvider : AiProvider {
        var request: AiRequest? = null
        var listener: ((AiStreamEvent) -> Unit)? = null
        override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
            this.request = request
            this.listener = listener
            return object : AiRequestHandle { override fun cancel() = Unit }
        }
        fun emit(event: AiStreamEvent) { listener?.invoke(event) }
    }
    private class PublicMemoryStore : EncryptedStore {
        override fun read(): ByteArray? = null
        override fun write(plaintext: ByteArray) { error("History saving must remain disabled in this fixture") }
        override fun delete(deleteKey: Boolean) = Unit
    }
    private fun visible(root: View): List<View> = if (!root.isShown) emptyList() else
        listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { visible(root.getChildAt(it)) } else emptyList()
    private fun hasButton(root: View, label: Int): Boolean = visible(root).filterIsInstance<TextView>().any {
        it.text == it.context.getString(label)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Public AI fixture did not reach the expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
}
