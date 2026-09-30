package dev.zeroinput.ime.handwriting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class HandwritingRequestTest {
    @Test fun invalidRequestsAreRejectedBeforeCopyOrWorkerCreation() {
        assertNull(HandwritingRequest.copyOf(emptyList()))
        assertNull(HandwritingRequest.copyOf(List(49) { floatArrayOf(.2f, .3f) }))
        assertNull(HandwritingRequest.copyOf(listOf(FloatArray(1026))))
        assertNull(HandwritingRequest.copyOf(listOf(floatArrayOf(.2f))))
        assertNull(HandwritingRequest.copyOf(listOf(floatArrayOf(Float.NaN, .3f))))
        assertNull(HandwritingRequest.copyOf(listOf(floatArrayOf(.2f, 1.1f))))
    }

    @Test fun requestOwnsAnIndependentCopyAndRunsOnlyOnce() {
        val source = floatArrayOf(.2f, .3f)
        val request = requireNotNull(HandwritingRequest.copyOf(listOf(source)))
        source.fill(.8f)
        var invoked = 0
        var owned: FloatArray? = null
        request.run {
            invoked++
            owned = it.first()
            assertArrayEquals(floatArrayOf(.2f, .3f), it.first(), 0f)
        }
        request.run { invoked++ }
        org.junit.Assert.assertEquals(1, invoked)
        assertArrayEquals(floatArrayOf(0f, 0f), owned, 0f)
        assertArrayEquals(floatArrayOf(.8f, .8f), source, 0f)
    }

    @Test fun queuedCancellationWipesStrokeAndSkipsRecognition() {
        val stroke = floatArrayOf(0.2f, 0.3f)
        val request = HandwritingRequest(listOf(stroke))
        request.cancelQueued()
        var invoked = false
        request.run { invoked = true }
        assertFalse(invoked)
        assertArrayEquals(floatArrayOf(0f, 0f), stroke, 0f)
    }

    @Test fun runningRequestWipesStrokeEvenWhenRecognitionFails() {
        val stroke = floatArrayOf(0.2f, 0.3f)
        val request = HandwritingRequest(listOf(stroke))
        var failed = false
        try {
            request.run {
                request.cancelQueued()
                assertArrayEquals(floatArrayOf(0.2f, 0.3f), stroke, 0f)
                error("recognition failure")
            }
        } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertArrayEquals(floatArrayOf(0f, 0f), stroke, 0f)
    }
}
