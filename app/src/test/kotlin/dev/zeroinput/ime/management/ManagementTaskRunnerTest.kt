package dev.zeroinput.ime.management

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ManagementTaskRunnerTest {
    @Test fun resultsAndFailuresAreDeliveredOnTheOwnerQueue() {
        val f = Fixture()
        f.runner.execute({ 42 }) { f.values += it }
        f.worker.drain()
        assertTrue(f.values.isEmpty())
        f.deliver()
        assertEquals(listOf(42), f.values)
        f.runner.execute<Int>({ throw IllegalStateException("private fixture") }) { f.values += it }
        f.worker.drain()
        assertTrue(f.failures.isEmpty())
        f.deliver()
        assertEquals(1, f.failures.size)
        assertFalse(f.busy)
    }

    @Test fun rejectedWorkRestoresControlsAndReportsExactlyOnceWithoutRetrying() {
        val f = Fixture()
        f.worker.shutdown()
        var mutations = 0
        f.runner.execute({ mutations++; 1 }) { f.values += it }
        f.deliver()
        assertEquals(0, mutations)
        assertEquals(1, f.failures.size)
        assertFalse(f.busy)
        assertTrue(f.values.isEmpty())
    }

    @Test fun cancellingSuppressesQueuedWorkAndPreviouslyPostedResults() {
        val f = Fixture()
        f.runner.execute({ 1 }) { f.values += it }
        f.worker.drain()
        f.runner.execute({ 2 }) { f.values += it }
        f.runner.cancel()
        f.worker.drain()
        f.deliver()
        assertTrue(f.values.isEmpty())
        assertTrue(f.failures.isEmpty())
        assertFalse(f.busy)
    }

    @Test fun newerRequestCannotBeOverwrittenByAnOlderCompletionOrFailure() {
        val f = Fixture()
        f.runner.execute<Int>({ throw IllegalStateException() }) { f.values += it }
        f.worker.drain()
        f.runner.execute({ 3 }) { f.values += it }
        f.worker.drain()
        f.deliver()
        assertEquals(listOf(3), f.values)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun inactiveOrClosedPageCannotStartOrReceiveWork() {
        val f = Fixture()
        f.active = false
        f.runner.execute({ 1 }) { f.values += it }
        f.active = true
        f.runner.execute({ 2 }) { f.values += it }
        f.worker.drain()
        f.runner.close()
        f.runner.execute({ 3 }) { f.values += it }
        f.deliver()
        assertTrue(f.values.isEmpty())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun cancellationRevokesTheCheckBorrowedByAnInFlightRepositoryWrite() {
        val f = Fixture()
        var current: (() -> Boolean)? = null
        f.runner.execute({ valid -> current = valid; 1 }) { f.values += it }
        f.worker.drain()
        assertTrue(checkNotNull(current).invoke())
        f.runner.cancel()
        assertFalse(checkNotNull(current).invoke())
    }

    @Test fun interruptedWorkerRetainsItsInterruptSignal() {
        val f = Fixture()
        try {
            f.runner.execute<Int>({ throw InterruptedException() }) { f.values += it }
            f.worker.drain()
            assertTrue(Thread.currentThread().isInterrupted)
            f.deliver()
            assertEquals(1, f.failures.size)
            assertFalse(f.busy)
        } finally { Thread.interrupted() }
    }

    private class Fixture {
        val worker = Queue()
        val ui = ArrayDeque<() -> Unit>()
        var active = true
        var busy = false
        val values = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val runner = ManagementTaskRunner(worker, { ui += it }, { active }, { busy = it }, { failures += it })
        fun deliver() { while (ui.isNotEmpty()) ui.removeFirst().invoke() }
    }

    private class Queue : AbstractExecutorService() {
        private var stopped = false
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            pending += command
        }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return pending.toMutableList().also { pending.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && pending.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
