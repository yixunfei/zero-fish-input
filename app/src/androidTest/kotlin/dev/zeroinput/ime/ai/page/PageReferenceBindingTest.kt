package dev.zeroinput.ime.ai.page

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ai.*
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConversationRepository
import org.junit.Assert.*
import org.junit.Test

class PageReferenceBindingTest {
    @Test fun invalidSessionOrContextDeliveryImmediatelyClearsLoadingAndCannotAttach() = onMain {
        for (changeContext in listOf(false, true)) Fixture().use { f ->
            f.binding.request()
            f.work.removeFirst().run()
            assertTrue(f.loading())
            if (changeContext) f.controller.newConversation() else f.allowed = false
            f.delivery.removeFirst().invoke()
            assertFalse(f.loading())
            f.binding.apply(listOf(0))
            assertTrue(f.controller.contextState.references.isEmpty())
        }
    }

    @Test fun supersededCompletionDoesNotClearTheNewCaptureAndOnlyNewSelectionAttaches() = onMain {
        Fixture().use { f ->
            f.binding.request()
            f.work.removeFirst().run()
            f.binding.request()
            f.delivery.removeFirst().invoke()
            assertTrue(f.loading())
            f.work.removeFirst().run()
            f.delivery.removeFirst().invoke()
            assertFalse(f.loading())
            f.binding.apply(listOf(0))
            assertEquals(listOf("public page"), f.controller.contextState.references.map { it.text })
        }
    }

    private class Fixture : AutoCloseable {
        val work = ArrayDeque<Runnable>()
        val delivery = ArrayDeque<() -> Unit>()
        var allowed = true
        private val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_ZeroInput_InputMethod)
        private val view = ZeroInputView(context).apply {
            renderSession(InputSessionState(privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE)))
        }
        private val broker = PageReferenceBroker({ work.addLast(it) }, { delivery.addLast(it); true }, { true })
        private val source = broker.attach(PageTextSource { _, _ -> PageTextSnapshot(listOf("public page"), false) })
        private val store = object : EncryptedStore {
            override fun read(): ByteArray? = null
            override fun write(plaintext: ByteArray) = error("No storage expected")
            override fun delete(deleteKey: Boolean) = error("No storage expected")
        }
        private val provider = object : AiProvider {
            override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle =
                error("No network expected")
        }
        val controller = AiWorkbenchController(AiCoordinator(provider), AiConversationRepository(store),
            { it.run() }, { null }, AiDataGeneration(), { it(); true }, { allowed }, {}, {}, {})
        val binding = AiPageReferenceBinding(broker, { true }, Handler(Looper.getMainLooper()), controller,
            { allowed }, { "public.fixture" }, { view }, {}, {})

        init {
            visible(view).first { it.contentDescription == context.getString(dev.zeroinput.ime.ui.R.string.ai_open) }
                .performClick()
        }

        fun loading() = visible(view).filterIsInstance<TextView>().any {
            it.text == context.getString(dev.zeroinput.ime.ui.R.string.ai_page_loading)
        }

        override fun close() { binding.close(); source.close(); view.release() }
    }

    companion object {
        private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else
            listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()

        private fun onMain(action: () -> Unit) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        }
    }
}
