package dev.zeroinput.ime.ai.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStage6SensorCaptureTest {
    @Test
    fun closingCaptureClearsPublishedAndLateBytes() {
        val buffer = TransientCaptureBuffer()
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4)
        val late = byteArrayOf(5, 6)

        buffer.publish(first)
        buffer.publish(second)
        buffer.close()
        buffer.publish(late)

        assertEquals(listOf(0, 0), first.map(Byte::toInt))
        assertEquals(listOf(0, 0), second.map(Byte::toInt))
        assertEquals(listOf(0, 0), late.map(Byte::toInt))
    }
}
