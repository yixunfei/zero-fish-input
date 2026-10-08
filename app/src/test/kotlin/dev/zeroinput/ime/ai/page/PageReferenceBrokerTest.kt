package dev.zeroinput.ime.ai.page

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class PageReferenceBrokerTest {
    @Test fun connectingOrReceivingInvalidationNeverCapturesAndDisabledCaptureNeverReads() {
        val f = Fixture()
        f.broker.attach(f.source)
        f.broker.invalidate()
        f.enabled = false
        f.capture()
        f.flush()
        assertEquals(0, f.reads)
        assertNull(f.result)
    }

    @Test fun revocationBeforeExecutionOrDeliveryRejectsOldSnapshots() {
        for (completed in listOf(false, true)) {
            val f = Fixture()
            f.broker.attach(f.source)
            f.capture()
            if (completed) f.worker.drain()
            f.broker.invalidate()
            f.flush()
            assertEquals(0, f.deliveries)
        }
    }

    @Test fun timeoutDisconnectionAndExecutorRejectionFailClosed() {
        val f = Fixture()
        val registration = f.broker.attach(f.source)
        f.capture()
        f.worker.drain()
        f.time += 5_001
        f.ui.drain()
        assertNull(f.result)
        f.capture()
        registration.close()
        f.flush()
        assertNull(f.result)
        var result: PageTextSnapshot? = PageTextSnapshot(listOf("placeholder"), false)
        val rejected = PageReferenceBroker(Executor { throw RejectedExecutionException() }, { it(); true }, { true })
        rejected.attach(f.source)
        rejected.capture("fixture") { _, value -> result = value }
        assertNull(result)
    }

    @Test fun aNewRequestInvalidatesOldDeliveryAndOnlyExplicitCallsReachTheSource() {
        val f = Fixture()
        f.broker.attach(f.source)
        f.capture()
        f.worker.drain()
        f.capture()
        f.flush()
        assertEquals(2, f.reads)
        assertEquals(1, f.deliveries)
        assertEquals(listOf("public"), f.result?.texts)
    }

    private class Fixture {
        val worker = Queue()
        val ui = Queue()
        var enabled = true
        var time = 0L
        var reads = 0
        var deliveries = 0
        var result: PageTextSnapshot? = null
        val source = PageTextSource { _, current ->
            assertTrue(current()); reads++; PageTextSnapshot(listOf("public"), false)
        }
        val broker = PageReferenceBroker(worker, { ui.execute(it); true }, { enabled }, { time })
        fun capture() { broker.capture("fixture") { _, value -> deliveries++; result = value } }
        fun flush() { worker.drain(); ui.drain() }
    }
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
