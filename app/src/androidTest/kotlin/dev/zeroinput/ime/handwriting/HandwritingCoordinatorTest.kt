package dev.zeroinput.ime.handwriting

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandwritingCoordinatorTest {
    @Test fun malformedRequestDoesNotInitializeOrInvokeTheRecognizer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val created = AtomicInteger()
        val delivered = AtomicInteger()
        instrumentation.runOnMainSync {
            val coordinator = HandwritingCoordinator(Handler(Looper.getMainLooper()), {
                created.incrementAndGet()
                error("Invalid strokes must not initialize inference")
            }, { values, failed ->
                assertTrue(values?.isEmpty() == true)
                assertTrue(!failed)
                delivered.incrementAndGet()
            })
            coordinator.request(List(49) { floatArrayOf(.2f, .3f) })
            coordinator.close()
        }
        assertEquals(0, created.get())
        assertEquals(1, delivered.get())
    }

    @Test fun replacedRecognitionOnlyDeliversNewStrokesAndWipesBothRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val buffers = java.util.concurrent.CopyOnWriteArrayList<FloatArray>()
        val results = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        var coordinator: HandwritingCoordinator? = null
        instrumentation.runOnMainSync {
            coordinator = HandwritingCoordinator(Handler(Looper.getMainLooper()), {
                object : HandwritingRecognizer {
                    override fun recognize(strokes: List<FloatArray>, cancelled: () -> Boolean): List<String> {
                        buffers.add(strokes.first())
                        if (buffers.size == 1) {
                            started.countDown()
                            var finished = false
                            while (!finished) {
                                try { finished = finish.await(5, TimeUnit.SECONDS) }
                                catch (_: InterruptedException) { /* Deliberately emulate uncancellable native inference. */ }
                            }
                        }
                        return if (strokes.first()[0] < .5f) listOf("中") else listOf("文")
                    }
                    override fun close() { closed.countDown() }
                }
            }, { values, _ -> results.add(values.orEmpty()); delivered.countDown() })
            coordinator?.request(listOf(floatArrayOf(.2f, .3f)))
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { coordinator?.request(listOf(floatArrayOf(.8f, .3f))) }
            finish.countDown()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
        } finally {
            finish.countDown()
            instrumentation.runOnMainSync { coordinator?.close() }
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(listOf("文")), results.toList())
        assertEquals(2, buffers.size)
        buffers.forEach { assertArrayEquals(floatArrayOf(0f, 0f), it, 0f) }
    }

    @Test fun recognitionFailureClosesFailedModelAndNextRequestCanRecover() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val closed = CountDownLatch(2)
        val created = AtomicInteger()
        var coordinator: HandwritingCoordinator? = null
        instrumentation.runOnMainSync {
            coordinator = HandwritingCoordinator(Handler(Looper.getMainLooper()), {
                val instance = created.incrementAndGet()
                object : HandwritingRecognizer {
                    override fun recognize(strokes: List<FloatArray>, cancelled: () -> Boolean): List<String> {
                        if (instance == 1) throw IllegalStateException("Public fixture failure")
                        return listOf("體")
                    }
                    override fun close() { closed.countDown() }
                }
            }, { values, unavailable ->
                if (unavailable) failed.countDown()
                else if (values == listOf("體")) recovered.countDown()
            })
            coordinator?.request(listOf(floatArrayOf(.2f, .3f)))
        }
        try {
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { coordinator?.request(listOf(floatArrayOf(.2f, .3f))) }
            assertTrue(recovered.await(5, TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { coordinator?.close() } }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(2, created.get())
    }

    @Test fun invalidatedRunningRecognitionCannotDeliverToLaterEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val delivered = AtomicInteger()
        var coordinator: HandwritingCoordinator? = null
        instrumentation.runOnMainSync {
            coordinator = HandwritingCoordinator(Handler(Looper.getMainLooper()), {
                object : HandwritingRecognizer {
                    override fun recognize(strokes: List<FloatArray>, cancelled: () -> Boolean): List<String> {
                        started.countDown()
                        while (true) {
                            try {
                                if (finish.await(2, TimeUnit.SECONDS)) break
                            } catch (_: InterruptedException) { /* Exercise a native call that finishes after cancellation. */ }
                        }
                        return listOf("中")
                    }
                    override fun close() { closed.countDown() }
                }
            }, { _, _ -> delivered.incrementAndGet() })
            coordinator?.request(listOf(floatArrayOf(0.2f, 0.3f)))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { coordinator?.invalidate() }
        finish.countDown()
        instrumentation.runOnMainSync { coordinator?.close() }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(0, delivered.get())
    }
}
