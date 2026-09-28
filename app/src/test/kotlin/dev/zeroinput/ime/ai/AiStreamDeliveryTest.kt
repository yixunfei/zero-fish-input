package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiStreamEvent
import org.junit.Assert.*
import org.junit.Test

class AiStreamDeliveryTest {
    @Test fun tokenBurstPostsOneCallbackAndDuplicateCompletionIsDiscarded() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<AiStreamEvent>()
        val delivery = AiStreamDelivery({ tasks.add(it); true }, events::add)
        repeat(1_000) { delivery.offer(AiStreamEvent.Delta("x")) }
        delivery.offer(AiStreamEvent.Completed("done"))
        delivery.offer(AiStreamEvent.Completed("duplicate"))
        assertEquals(1, tasks.size)
        tasks.single()()
        assertEquals(1_000, (events.first() as AiStreamEvent.Delta).text.length)
        assertEquals(AiStreamEvent.Completed("done"), events.last())
        assertEquals(2, events.size)
    }
    @Test fun closedDeliveryErasesQueuedText() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<AiStreamEvent>()
        val delivery = AiStreamDelivery({ tasks.add(it); true }, events::add)
        delivery.offer(AiStreamEvent.Delta("fixture"))
        delivery.close()
        tasks.single()()
        assertTrue(events.isEmpty())
    }
}
