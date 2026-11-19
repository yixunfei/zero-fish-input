package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiProtocol
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlatformExchangeCancellationTest {
    @Test fun cancellationBeforeOrDuringPreparationShutsDownTheLateClientWithoutSending() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            repeat(40) { index ->
                val exchange = OpenAiCompatibleProvider.PlatformExchange(URI("https://example.invalid/v1/models"))
                val start = CountDownLatch(1)
                val cancel = executor.submit { start.await(); exchange.disconnect() }
                if (index % 2 == 0) { start.countDown(); cancel.get(2, TimeUnit.SECONDS) }
                else start.countDown()
                try { exchange.prepare("GET", "public-fixture", "application/json", 1000, null, AiProtocol.CHAT_COMPLETIONS) }
                catch (_: IOException) { }
                cancel.get(2, TimeUnit.SECONDS)
                assertThrows(IOException::class.java) { exchange.send(null) }
                assertTrue(exchange.resourcesReleased())
            }
        } finally { executor.shutdownNow() }
    }
}
