package dev.zeroinput.ime.ai

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.testing.DeviceTouch
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConversationRepository
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
            if (action != AiAction.TRANSLATE) DeviceTouch.tap { f.button(UiR.string.ai_submit) }
            onMain {
                assertEquals(action, f.provider.request?.action)
                assertTrue(f.inserted.isEmpty())
                assertFalse(f.button(UiR.string.ai_insert).isEnabled)
                f.provider.emit(AiStreamEvent.Delta("public "))
                f.provider.emit(AiStreamEvent.Completed("public answer"))
            }
            await { f.button(UiR.string.ai_insert).isEnabled }
            onMain { assertTrue(f.inserted.isEmpty()) }
            DeviceTouch.tap { f.button(UiR.string.ai_insert) }
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
        onMain { assertFalse(f.button(UiR.string.ai_insert).isEnabled); assertNull(f.controller.consumeResult()) }
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
        private val worker = Executors.newSingleThreadExecutor()
        private val handler = Handler(Looper.getMainLooper())
        val controller = AiWorkbenchController(AiCoordinator(provider), AiConversationRepository(PublicMemoryStore()), worker,
            { AiConfiguration(enabled = true, networkAllowed = true) }, AiDataGeneration(),
            { handler.post(it) }, { allowed && panel.isAiOpen }, panel::renderAi,
            panel::renderAiConversations, panel::renderAiConversation)

        init {
            panel.renderSession(ready)
            panel.onAiSubmit = controller::submit
            panel.onAiCancel = controller::stop
            panel.onAiInsert = {
                controller.consumeResult()?.let(inserted::add)
                panel.returnToKeyboard()
            }
            panel.onAiVisibilityChanged = { if (!it) controller.invalidate() }
        }
        fun button(label: Int) = visible(panel).filterIsInstance<TextView>().first { it.text == it.context.getString(label) }
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
