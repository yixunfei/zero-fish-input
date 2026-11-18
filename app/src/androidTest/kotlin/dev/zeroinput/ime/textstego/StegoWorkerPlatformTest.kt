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
    @Test fun cancelledAndDuplicateDeliveriesClearOwnedPlaintext() {
        val operation = StegoCryptoOperation(1, SystemClock.elapsedRealtime() + 30_000)
        val first = "public first result".toCharArray()
        val duplicate = "public duplicate".toCharArray()
        operation.offer(StegoDelivery().apply { result = first })
        operation.offer(StegoDelivery().apply { result = duplicate })
        assertTrue(duplicate.all { it == '\u0000' })
        operation.close()
        assertTrue(first.all { it == '\u0000' })
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
