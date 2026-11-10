package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test

class AiChatQueueTest {
    private val posted = mutableListOf<() -> Unit>()
    private val sent = mutableListOf<String>()
    private var ready = true
    private var cancellations = 0
    private var entries = emptyList<AiChatQueue.Message>()
    private val queue = AiChatQueue({ ready }, { posted.add(it); true }, { sent += it.text },
        { cancellations++ }, { values, _ -> entries = values })
    private fun message(text: String) = AiChatQueue.Message(AiAction.ASK, text, null)

    @Test fun completedDrainsInOrderOnceAndAllowsEditingBeforeDispatch() {
        queue.send(message("first")); queue.send(message("second")); queue.send(message("third"))
        queue.completed(); queue.completed()
        assertEquals(1, posted.size)
        assertEquals("second", queue.remove(0)?.text)
        posted.removeAt(0)()
        assertEquals(listOf("first", "third"), sent)
    }

    @Test fun limitsBoundBothMessageCountAndCombinedCharacters() {
        queue.send(message("active"))
        repeat(4) { assertTrue(queue.send(message("next"))) }
        assertFalse(queue.send(message("fifth")))
        queue.failed(); queue.send(message("active"))
        assertTrue(queue.send(message("x".repeat(AiLimits.MAX_INPUT_CHARS))))
        assertFalse(queue.send(message("overflow")))
        assertEquals(1, entries.size)
    }

    @Test fun resetAndFailureInvalidateAlreadyPostedDrain() {
        for (reset in listOf(true, false)) {
            queue.send(message("first")); queue.send(message("stale")); queue.completed()
            if (reset) queue.reset() else queue.failed()
            posted.removeAt(0)()
            assertFalse(queue.busy)
            assertTrue(entries.isEmpty())
        }
        assertFalse("stale" in sent)
    }

    @Test fun changedPermissionAtDrainDiscardsRemainingMessages() {
        queue.send(message("first")); queue.send(message("stale")); queue.completed()
        ready = false
        posted.removeAt(0)()
        assertEquals(listOf("first"), sent)
        assertTrue(entries.isEmpty())
    }

    @Test fun interruptRevokesOldDrainAndCancelsBeforeReplacement() {
        queue.send(message("first")); queue.send(message("stale")); queue.completed()
        assertTrue(queue.send(message("replacement"), interrupt = true))
        posted.removeAt(0)()
        assertEquals(listOf("first", "replacement"), sent)
        assertEquals(1, cancellations)
        assertTrue(queue.busy)
    }

    @Test fun rejectedDispatcherStopsWithoutSubmittingOrKeepingDrafts() {
        val local = AiChatQueue({ true }, { false }, { sent += it.text }, {}, { values, _ -> entries = values })
        local.send(message("first")); local.send(message("second")); local.completed()
        assertEquals(listOf("first"), sent)
        assertTrue(entries.isEmpty())
        assertFalse(local.busy)
    }

    @Test fun invalidReplacementDoesNotCancelAnExistingTask() {
        queue.send(message("first"))
        assertFalse(queue.send(message(" "), interrupt = true))
        assertTrue(queue.busy)
        assertEquals(0, cancellations)
    }
}
