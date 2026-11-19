package dev.zeroinput.ime.textstego

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoWorkerPlatformTest {
    @Test fun allocationFailureProducesASafeDeliveryAndWipesAnyPartialResult() {
        val chars = "public partial fixture".toCharArray()
        val outputs = StegoOutputs("cover".toCharArray(), chars, byteArrayOf(1), "public partial carrier")
        val delivery = StegoDelivery().apply { result = chars; encoded = "public partial carrier"; this.outputs = outputs }
        delivery.compute { throw OutOfMemoryError("public allocation fixture") }
        assertTrue(delivery.memoryUnavailable)
        assertNull(delivery.result)
        assertNull(delivery.encoded)
        assertNull(delivery.outputs)
        StegoOutput.entries.forEach { assertNull(outputs.copy(it)) }
        assertTrue(chars.all { it == '\u0000' })
        val operation = StegoCryptoOperation(1, SystemClock.elapsedRealtime() + 30_000)
        operation.offer(delivery)
        assertSame(delivery, operation.take())
        operation.close()
        val fatal = StegoDelivery()
        assertThrows(AssertionError::class.java) { fatal.compute { throw AssertionError("public fatal fixture") } }
        fatal.close()
    }

    @Test fun cancelledAndDuplicateDeliveriesClearOwnedPlaintext() {
        val operation = StegoCryptoOperation(1, SystemClock.elapsedRealtime() + 30_000)
        val first = "public first result".toCharArray()
        val duplicate = "public duplicate".toCharArray()
        val outputs = StegoOutputs("cover".toCharArray(), first, byteArrayOf(1), "public fixture")
        operation.offer(StegoDelivery().apply { result = first; this.outputs = outputs })
        operation.offer(StegoDelivery().apply { result = duplicate })
        assertTrue(duplicate.all { it == '\u0000' })
        operation.close()
        assertTrue(first.all { it == '\u0000' })
        StegoOutput.entries.forEach { assertNull(outputs.copy(it)) }
        val late = "public late result".toCharArray()
        operation.offer(StegoDelivery().apply { result = late })
        assertTrue(late.all { it == '\u0000' })
        assertNull(operation.take())
    }

    @Test fun cancellationDoesNotFreeRunningCryptoOrSourceSlots() {
        for (executor in listOf(StegoCryptoWorker.executor, StegoSourceWorker.executor)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val operation = StegoCryptoOperation(1, SystemClock.elapsedRealtime() + 30_000)
            try {
                executor.execute { started.countDown(); try { release.await(5, TimeUnit.SECONDS) } finally { finished.countDown() } }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                operation.close()
                assertThrows(RejectedExecutionException::class.java) { executor.execute {} }
                assertFalse(operation.current())
            } finally { release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)) }
        }
    }
}
