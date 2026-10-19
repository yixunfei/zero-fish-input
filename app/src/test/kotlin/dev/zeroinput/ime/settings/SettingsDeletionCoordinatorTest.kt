package dev.zeroinput.ime.settings

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SettingsDeletionCoordinatorTest {
    @Test fun confirmedDeletionCompletesAfterTheOldPageDetachesAndIsDeliveredOnceToItsReplacement() {
        val worker = ManualWorker()
        val ui = ArrayDeque<() -> Unit>()
        var deletions = 0
        val coordinator = SettingsDeletionCoordinator({ ui.addLast(it) }, { _, done -> deletions++; done(true) }, worker)
        val oldResults = mutableListOf<SettingsDeletionResult>()
        val oldPage = coordinator.observe(listener = oldResults::add)
        coordinator.submit(SettingsDeletion.PERSONALIZATION)
        oldPage.close()
        worker.runAll()
        ui.drain()
        assertEquals(1, deletions)
        assertTrue(oldResults.isEmpty())
        val replacementResults = mutableListOf<SettingsDeletionResult>()
        coordinator.observe(listener = replacementResults::add)
        ui.drain()
        assertEquals(listOf(SettingsDeletionResult(SettingsDeletion.PERSONALIZATION, true)), replacementResults)
        coordinator.observe(listener = { fail("Already consumed result") })
        ui.drain()
        coordinator.close()
    }

    @Test fun partialDeletionFailureIsRetainedWhenTheActivityBecomesInactiveBeforeUiDelivery() {
        val worker = ManualWorker()
        val ui = ArrayDeque<() -> Unit>()
        val coordinator = SettingsDeletionCoordinator({ ui.addLast(it) }, { _, _ -> error("fixture") }, worker)
        var active = true
        coordinator.observe({ active }) { fail("Inactive page received a result") }
        coordinator.submit(SettingsDeletion.SECURE_CLIPBOARD)
        worker.runAll()
        active = false
        ui.drain()
        val results = mutableListOf<SettingsDeletionResult>()
        coordinator.observe(listener = results::add)
        ui.drain()
        assertEquals(listOf(SettingsDeletionResult(SettingsDeletion.SECURE_CLIPBOARD, false)), results)
        coordinator.close()
    }

    @Test fun duplicateRequestAndDuplicateAsyncCompletionCannotRepeatDeletionOrResults() {
        val worker = ManualWorker()
        val ui = ArrayDeque<() -> Unit>()
        var complete: ((Boolean) -> Unit)? = null
        var requests = 0
        val coordinator = SettingsDeletionCoordinator({ ui.addLast(it) }, { _, done -> requests++; complete = done }, worker)
        val results = mutableListOf<SettingsDeletionResult>()
        coordinator.submit(SettingsDeletion.AI)
        coordinator.submit(SettingsDeletion.AI)
        worker.runAll()
        coordinator.observe(listener = results::add)
        checkNotNull(complete)(false)
        checkNotNull(complete)(true)
        ui.drain()
        assertEquals(1, requests)
        assertEquals(listOf(SettingsDeletionResult(SettingsDeletion.AI, false)), results)
        coordinator.close()
    }

    @Test fun rejectedDeletionReportsFailureAfterTheSettingsPageReturns() {
        val worker = ManualWorker().apply { shutdown() }
        val coordinator = SettingsDeletionCoordinator({ it() }, { _, _ -> fail("Rejected work ran") }, worker)
        coordinator.submit(SettingsDeletion.PERSONALIZATION)
        val results = mutableListOf<SettingsDeletionResult>()
        coordinator.observe(listener = results::add)
        assertEquals(listOf(SettingsDeletionResult(SettingsDeletion.PERSONALIZATION, false)), results)
        coordinator.close()
    }

    private fun ArrayDeque<() -> Unit>.drain() { while (isNotEmpty()) removeFirst().invoke() }

    private class ManualWorker : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also { tasks.clear(); stopped = true }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
