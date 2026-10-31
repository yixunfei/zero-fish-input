package dev.zeroinput.ime.clipboard

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardSelectionTransferTest {
    @Test
    fun deadlineRejectsAndWipesDraftEvenWhenTheExpiryCallbackHasNotRun() {
        var now = 100L
        val buffer = "public selection fixture".toCharArray()
        val draft = ClipboardImportDraft(ClipboardImportRequest(buffer), 7L)
        ClipboardSelectionTransfer { now }.use { transfer ->
            val token = transfer.offer(draft)
            now += 5_000L
            assertNull(transfer.take(token))
            assertEquals(ClipboardImportRequest.Phase.CLOSED, draft.request.phase)
            assertTrue(buffer.all { it == '\u0000' })
        }
    }

    @Test
    fun liveTokenTransfersOwnershipExactlyOnceAndPreservesDeletionGeneration() {
        var now = 0L
        val draft = ClipboardImportDraft(ClipboardImportRequest("public fixture".toCharArray()), 9L)
        ClipboardSelectionTransfer { now }.use { transfer ->
            val token = transfer.offer(draft)
            now = 4_999L
            assertNull(transfer.take(null))
            assertNull(transfer.take("incorrect-token"))
            assertSame(draft, transfer.take(token))
            assertEquals(9L, draft.generation)
            assertNull(transfer.take(token))
        }
        assertEquals(ClipboardImportRequest.Phase.REVIEW, draft.request.phase)
        draft.request.close()
    }

    @Test
    fun replacementRevokesOldTokenWipesOldBufferAndStartsANewDeadline() {
        var now = 0L
        val oldBuffer = "public first".toCharArray()
        val old = ClipboardImportDraft(ClipboardImportRequest(oldBuffer), 1L)
        val replacement = ClipboardImportDraft(ClipboardImportRequest("public second".toCharArray()), 2L)
        ClipboardSelectionTransfer { now }.use { transfer ->
            val oldToken = transfer.offer(old)
            now = 4_000L
            val newToken = transfer.offer(replacement)
            assertTrue(oldBuffer.all { it == '\u0000' })
            now = 5_000L
            assertNull(transfer.take(oldToken))
            assertSame(replacement, transfer.take(newToken))
        }
        replacement.request.close()
    }
}
