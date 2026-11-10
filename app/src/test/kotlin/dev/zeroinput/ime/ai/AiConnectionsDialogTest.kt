package dev.zeroinput.ime.ai

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConnectionRepository
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiConnectionsDialogTest {
    @Test fun navigationAndCatalogReviewNeverSearchAndRevisionsRevokePendingApply() {
        val owner = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = owner.get(); activity.setTheme(R.style.Theme_ZeroInput); owner.setup()
        val repository = AiConnectionRepository(Store())
        val fixture = AiConnection("fixture", "Public search", AiConnectionKind.BRAVE, AiConnection.BRAVE_ENDPOINT, "public-token")
        repository.write(0, AiConnectionLibrary(connections = listOf(fixture))) { true }
        var networkCalls = 0; var applied = 0
        val transport = object : AiRemoteTransport {
            override fun mcp(connection: AiConnection, body: String, session: String?, cancellation: AiRemoteCancellation): AiRemoteReply { networkCalls++; error("No connection expected") }
            override fun search(connection: AiConnection, query: String, cancellation: AiRemoteCancellation): AiRemoteReply { networkCalls++; error("No query expected") }
        }
        val worker = ArrayDeque<Runnable>(); val revisions = AiDataGeneration()
        val review = AiConnectionsDialog(activity, repository, Executor { worker.add(it) }, Executor { worker.add(it) },
            AiDataGeneration(), revisions, Handler(Looper.getMainLooper()), { true }, { true }, {},
            { bundle, selected -> assertEquals(setOf("web/brave"), selected); applied++; bundle.close(); true }, transport)
        fun drain() { while (worker.isNotEmpty()) { worker.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle() } }
        try {
            review.show(); drain()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            fun click(label: Int) = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .single { it.text.toString() == activity.getString(label) }.performClick()
            assertEquals(0, networkCalls); assertEquals(0, applied)
            click(R.string.ai_remote_select_search); drain()
            assertEquals(0, networkCalls)
            descendants(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().performClick()
            click(R.string.ai_remote_apply)
            revisions.invalidate(); drain()
            assertEquals(0, applied)
            click(R.string.ai_remote_select_search); drain()
            descendants(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().performClick()
            click(R.string.ai_remote_apply); drain()
            assertEquals(1, applied); assertEquals(0, networkCalls)
        } finally { review.close(); owner.pause().stop().destroy() }
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
    private class Store : EncryptedStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}
