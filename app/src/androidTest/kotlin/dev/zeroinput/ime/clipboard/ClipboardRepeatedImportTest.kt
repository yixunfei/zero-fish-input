package dev.zeroinput.ime.clipboard

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ZeroInputApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardRepeatedImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph

    @Test fun newSelectionReplacesPendingAuthenticationButNeverInheritsIt() {
        val oldText = "first public fixture".toCharArray()
        val old = ClipboardImportRequest(oldText)
        var token = ""
        instrumentation.runOnMainSync {
            token = graph.clipboardSelectionTransfer.offer(ClipboardImportDraft(old, 0))
        }
        val activity = instrumentation.startActivitySync(selectionIntent(token)) as ClipboardSelectionImportActivity
        try {
            instrumentation.runOnMainSync {
                assertTrue(old.beginAuthentication())
                val fresh = ClipboardImportRequest("second public fixture".toCharArray())
                val freshToken = graph.clipboardSelectionTransfer.offer(ClipboardImportDraft(fresh, 0))
                val incoming = selectionIntent(freshToken)
                instrumentation.callActivityOnNewIntent(activity, incoming)

                assertFalse(activity.isFinishing)
                assertEquals(ClipboardImportRequest.Phase.CLOSED, old.phase)
                assertTrue(oldText.all { it == '\u0000' })
                assertFalse(old.authorize())
                assertEquals(ClipboardImportRequest.Phase.REVIEW, fresh.phase)
                assertFalse(fresh.authorize())
                assertFalse(fresh.beginSave())
                assertNull(fresh.copyText())
                assertNull(graph.clipboardSelectionTransfer.take(freshToken))
                assertNull(incoming.extras)

                instrumentation.callActivityOnNewIntent(activity, selectionIntent("invalid-token"))
                assertTrue(activity.isFinishing)
                assertEquals(ClipboardImportRequest.Phase.CLOSED, fresh.phase)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); graph.clipboardSelectionTransfer.close() }
        }
    }

    private fun selectionIntent(token: String) = Intent(context, ClipboardSelectionImportActivity::class.java)
        .putExtra(ClipboardSelectionImportActivity.EXTRA_TOKEN, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
