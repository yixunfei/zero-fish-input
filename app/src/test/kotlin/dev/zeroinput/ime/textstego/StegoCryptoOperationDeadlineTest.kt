package dev.zeroinput.ime.textstego

import android.app.Application
import android.os.SystemClock
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StegoCryptoOperationDeadlineTest {
    @Test fun resultQueuedBeforeDeadlineIsWipedIfTakenAfterItExpires() {
        val plaintext = "public deadline fixture".toCharArray()
        val outputs = StegoOutputs(null, plaintext, null, "public carrier")
        val delivery = StegoDelivery().apply { result = plaintext; this.outputs = outputs }
        StegoCryptoOperation(1L, SystemClock.elapsedRealtime() + 1_000).use { lease ->
            lease.offer(delivery)
            assertTrue(lease.current())
            ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(1))
            assertFalse(lease.current())
            assertNull(lease.take())
            assertTrue(plaintext.all { it == '\u0000' })
            assertNull(outputs.copy(StegoOutput.PLAINTEXT))
        }
    }
}
