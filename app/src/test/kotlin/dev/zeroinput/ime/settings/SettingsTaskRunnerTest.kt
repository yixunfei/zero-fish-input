package dev.zeroinput.ime.settings

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class SettingsTaskRunnerTest {
    @Test fun successfulMutationIsDeliveredOnlyOnTheOwnerQueue() {
        val f = Fixture()
        var value: String? = null
        f.runner.execute({ "saved" }) { value = it }
        f.worker.drain()
        assertNull(value)
        f.ui.drain()
        assertEquals("saved", value)
        assertEquals(0, f.failures)
    }

    @Test fun failedBackgroundOperationReportsFailureWithoutSuccessOrUncaughtException() {
        val f = Fixture()
        f.runner.execute<Unit>({ throw IllegalStateException("private fixture") }) { f.successes++ }
        f.worker.drain()
        assertEquals(0, f.failures)
        f.ui.drain()
        assertEquals(1, f.failures)
        assertEquals(0, f.successes)
    }

    @Test fun rejectedSubmissionReportsFailureWithoutRunningTheMutation() {
        val f = Fixture()
        f.worker.rejected = true
        var mutated = false
        f.runner.execute({ mutated = true }) { f.successes++ }
        f.ui.drain()
        assertFalse(mutated)
        assertEquals(1, f.failures)
        assertEquals(0, f.successes)
    }

    @Test fun destroyedOwnerSkipsQueuedMutationAndSuppressesAlreadyPostedResults() {
        val f = Fixture()
        var mutations = 0
        f.runner.execute({ mutations++ }) { f.successes++ }
        f.worker.drain()
        f.runner.execute({ mutations++ }) { f.successes++ }
        f.runner.close()
        f.worker.drain()
        f.ui.drain()
        assertEquals(1, mutations)
        assertEquals(0, f.successes)
        assertEquals(0, f.failures)
    }

    @Test fun finishingOwnerDoesNotReceiveSuccessOrFailureCallbacks() {
        val f = Fixture()
        f.runner.execute({ Unit }) { f.successes++ }
        f.runner.execute<Unit>({ throw IllegalStateException() }) { f.successes++ }
        f.worker.drain()
        f.active = false
        f.ui.drain()
        assertEquals(0, f.successes)
        assertEquals(0, f.failures)
    }

    @Test fun interruptedOperationPreservesTheWorkersCancellationSignal() {
        val f = Fixture()
        try {
            f.runner.execute<Unit>({ throw InterruptedException() }) { f.successes++ }
            f.worker.drain()
            assertTrue(Thread.currentThread().isInterrupted)
            f.ui.drain()
            assertEquals(1, f.failures)
            assertEquals(0, f.successes)
        } finally { Thread.interrupted() }
    }

    private class Fixture {
        val worker = Queue()
        val ui = Queue()
        var active = true
        var successes = 0
        var failures = 0
        val runner = SettingsTaskRunner(worker, { ui.execute(it) }, { active }, { failures++ })
    }

    private class Queue : Executor {
        private val pending = ArrayDeque<Runnable>()
        var rejected = false
        override fun execute(command: Runnable) {
            if (rejected) throw RejectedExecutionException()
            pending.addLast(command)
        }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
