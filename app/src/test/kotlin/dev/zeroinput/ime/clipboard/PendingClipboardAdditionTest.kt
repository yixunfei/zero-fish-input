package dev.zeroinput.ime.clipboard

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PendingClipboardAdditionTest {
    @Test fun delayedWriteKeepsTheValueUntilConsumedAndThenWipesIt() {
        val buffer = "public fixture".toCharArray()
        val draft = PendingClipboardAddition(buffer)
        val queuedWrite = { draft.consume { String(it) } }
        assertEquals("public fixture", String(buffer))
        assertEquals("public fixture", queuedWrite())
        assertTrue(buffer.all { it == '\u0000' })
        assertFalse(draft.isActive())
        assertThrows(CancellationException::class.java) { queuedWrite() }
    }

    @Test fun authenticationCancellationOrQueueRejectionWipesWithoutRunningTheWrite() {
        val buffer = "public fixture".toCharArray()
        val draft = PendingClipboardAddition(buffer)
        draft.close()
        draft.close()
        assertTrue(buffer.all { it == '\u0000' })
        assertThrows(CancellationException::class.java) { draft.consume { fail("Must not write") } }
    }

    @Test fun cancellationDuringWriteInvalidatesTheOperationWithoutRacingItsBuffer() {
        val buffer = "public fixture".toCharArray()
        val draft = PendingClipboardAddition(buffer)
        draft.consume {
            draft.close()
            assertFalse(draft.isActive())
            assertEquals("public fixture", String(it))
        }
        assertTrue(buffer.all { it == '\u0000' })
    }

    @Test fun failedWriteStillWipesTheOwnedBuffer() {
        val buffer = "public fixture".toCharArray()
        val draft = PendingClipboardAddition(buffer)
        assertThrows(IllegalStateException::class.java) { draft.consume { error("fixture failure") } }
        assertTrue(buffer.all { it == '\u0000' })
        assertFalse(draft.isActive())
    }
}
