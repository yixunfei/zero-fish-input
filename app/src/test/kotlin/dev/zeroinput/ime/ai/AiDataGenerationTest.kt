package dev.zeroinput.ime.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDataGenerationTest {
    @Test
    fun clearingDataInvalidatesAQueuedConversationWrite() {
        val generation = AiDataGeneration()
        val queuedWriteGeneration = generation.current()
        assertTrue(generation.isCurrent(queuedWriteGeneration))
        generation.invalidate()
        assertFalse(generation.isCurrent(queuedWriteGeneration))
    }
}
