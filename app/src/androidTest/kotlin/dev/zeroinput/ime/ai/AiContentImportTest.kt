package dev.zeroinput.ime.ai

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiContentImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph

    @Test fun unsupportedActionsMimeTypesAndOversizedTextFailClosed() {
        assertNull(AiImportIntent.text(Intent(Intent.ACTION_VIEW).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "fixture")))
        assertNull(AiImportIntent.text(Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra(Intent.EXTRA_TEXT, "fixture")))
        assertNull(AiImportIntent.text(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "x".repeat(AiLimits.MAX_INPUT_CHARS + 1))))
        assertEquals("fixture", AiImportIntent.text(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "fixture")))
        assertThrows(IllegalArgumentException::class.java) {
            AiAttachmentReader.read(context.contentResolver, Uri.parse("file:///private"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiAttachmentReader.read(context.contentResolver, Uri.parse("https://example.test/file"))
        }
    }

    @Test fun shareNeedsConfirmationAndNeverSendsOrReturnsText() {
        val learning = graph.settings.learningEnabled
        val incognito = graph.settings.incognitoMode
        var activity: AiComposeActivity? = null
        try {
            onMain { graph.settings.learningEnabled = true; graph.settings.incognitoMode = false }
            graph.aiContentInbox.clear()
            val intent = Intent(context, AiComposeActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "public selected content")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity = instrumentation.startActivitySync(intent) as AiComposeActivity
            val screen = activity
            await { screen.hasWindowFocus() }
            onMain {
                assertTrue(screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(graph.aiContentInbox.available())
                views(screen.window.decorView).filterIsInstance<TextView>().single {
                    it.text == context.getString(R.string.ai_import_confirm)
                }.performClick()
            }
            await { screen.isDestroyed }
            val imported = checkNotNull(graph.aiContentInbox.take())
            assertEquals("public selected content", String(imported.text))
            imported.close()
            assertNull(graph.aiContentInbox.take())
        } finally {
            activity?.let { onMain { it.finish() } }
            graph.aiContentInbox.clear()
            onMain { graph.settings.learningEnabled = learning; graph.settings.incognitoMode = incognito }
        }
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Import fixture did not reach expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
