package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.CandidateScorer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class AsyncCandidateRankerTest {
    @Test fun `worker construction failure wipes input and permits later recovery`() {
        val worker = ManualExecutor()
        var attempts = 0
        val completed = mutableListOf<Long>()
        val ranker = AsyncCandidateRanker({ Scorer() }, { it.run(); true },
            { revision, _ -> completed += revision }, {
                if (attempts++ == 0) throw IllegalStateException("Fixture worker unavailable")
                worker
            }, { 0L })
        ranker.setEnabled(true)
        val prefix = "学习".toCharArray()
        val candidates = words()
        ranker.request(1, prefix, candidates)
        assertTrue(prefix.all { it == '\u0000' })
        assertTrue(candidates.all { word -> word.all { it == '\u0000' } })
        ranker.request(2, "学习".toCharArray(), words())
        worker.run()
        assertEquals(listOf(2L), completed)
        ranker.close()
        worker.run()
    }

    @Test fun `disabled requests do not create runtime or executor and wipe their buffers`() {
        val ranker = AsyncCandidateRanker({ error("Unexpected model") }, { error("Unexpected delivery") },
            { _, _ -> error("Unexpected result") }, { error("Unexpected worker") })
        val prefix = "学习".toCharArray()
        val words = words()
        ranker.request(1, prefix, words)
        ranker.close()
        assertTrue(prefix.all { it == '\u0000' } && words.all { w -> w.all { it == '\u0000' } })
    }

    @Test fun `latest pending request wins with bounded delivery and all inputs wiped`() {
        val worker = ManualExecutor()
        val delivery = ArrayDeque<Runnable>()
        val completed = mutableListOf<Long>()
        var creations = 0
        val scorer = Scorer()
        val ranker = AsyncCandidateRanker({ creations++; scorer }, { delivery.add(it); true },
            { revision, _ -> completed += revision }, { worker }, { 0L })
        ranker.setEnabled(true)
        val prefix = "学习".toCharArray()
        ranker.request(1, prefix, words())
        ranker.request(2, "学习".toCharArray(), words())
        assertTrue(prefix.all { it == '\u0000' })
        assertEquals(1, worker.tasks.size)
        worker.run()
        ranker.request(3, "学习".toCharArray(), words())
        worker.run()
        assertEquals(1, delivery.size)
        delivery.first().run()
        delivery.removeFirst().run() // A repeated callback must not re-deliver.
        assertEquals(listOf(3L), completed)
        assertEquals(1, creations)
        assertTrue(checkNotNull(scorer.borrowed).all { it == '\u0000' })
        ranker.setEnabled(false)
        worker.run()
        assertEquals(1, scorer.closes)
        ranker.close()
        worker.run()
    }

    @Test fun `cancel during inference and after posting prevents stale delivery`() {
        val worker = ManualExecutor()
        val delivery = ArrayDeque<Runnable>()
        var count = 0
        lateinit var ranker: AsyncCandidateRanker
        val scorer = Scorer()
        ranker = AsyncCandidateRanker({ scorer }, { delivery.add(it); true }, { _, _ -> count++ }, { worker }, { 0L })
        ranker.setEnabled(true)
        scorer.during = { ranker.cancel() }
        ranker.request(1, "学习".toCharArray(), words())
        worker.run()
        assertTrue(delivery.isEmpty())
        scorer.during = {}
        ranker.request(2, "学习".toCharArray(), words())
        worker.run()
        ranker.close()
        delivery.forEach { it.run() }
        worker.run()
        assertEquals(0, count)
        assertEquals(1, scorer.closes)
    }

    @Test fun `deadline rejection and load failure keep original candidates usable`() {
        val worker = ManualExecutor()
        var now = 0L
        val scorer = Scorer().apply { during = { now = AsyncCandidateRanker.DEADLINE_NANOS + 1 } }
        val ranker = AsyncCandidateRanker({ scorer }, { error("Late result") }, { _, _ -> error("Late result") }, { worker }, { now })
        ranker.setEnabled(true)
        ranker.request(1, "学习".toCharArray(), words())
        worker.run()
        ranker.close()
        worker.run()
        val rejected = AsyncCandidateRanker({ error("Rejected model") }, { false }, { _, _ -> },
            { ManualExecutor().apply { shutdown() } })
        rejected.setEnabled(true)
        val prefix = "学习".toCharArray()
        rejected.request(2, prefix, words())
        assertTrue(prefix.all { it == '\u0000' })
        rejected.close()
    }

    @Test fun `closing during cold load disposes newly created session`() {
        val worker = ManualExecutor()
        val scorer = Scorer()
        lateinit var ranker: AsyncCandidateRanker
        ranker = AsyncCandidateRanker({ ranker.close(); scorer }, { error("Closed delivery") }, { _, _ -> }, { worker }, { 0L })
        ranker.setEnabled(true)
        ranker.request(1, "学习".toCharArray(), words())
        worker.run()
        assertEquals(1, scorer.closes)
        assertNull(scorer.borrowed)
    }

    @Test fun `delayed main delivery and failed dispatcher cannot promote candidates`() {
        val worker = ManualExecutor()
        val delivery = ArrayDeque<Runnable>()
        var now = 0L
        var reject = true
        val ranker = AsyncCandidateRanker({ Scorer() }, {
            if (reject) throw IllegalStateException("Fixture dispatcher unavailable")
            delivery.add(it); true
        }, { _, _ -> error("Expired result") }, { worker }, { now })
        ranker.setEnabled(true)
        ranker.request(1, "学习".toCharArray(), words())
        worker.run()
        reject = false
        ranker.request(2, "学习".toCharArray(), words())
        worker.run()
        assertEquals(1, delivery.size)
        now = AsyncCandidateRanker.DEADLINE_NANOS + 1
        delivery.removeFirst().run()
        ranker.close()
        worker.run()
    }

    @Test fun `unavailable runtime is not retried on each key and restarts after disabling`() {
        val worker = ManualExecutor()
        var attempts = 0
        val ranker = AsyncCandidateRanker({ attempts++; throw IllegalStateException("Fixture model unavailable") },
            { error("Unavailable model delivery") }, { _, _ -> }, { worker }, { 0L })
        ranker.setEnabled(true)
        repeat(3) { ranker.request(it.toLong(), "学习".toCharArray(), words()); worker.run() }
        assertEquals(1, attempts)
        ranker.setEnabled(false)
        worker.run()
        ranker.setEnabled(true)
        ranker.request(4, "学习".toCharArray(), words())
        worker.run()
        assertEquals(2, attempts)
        ranker.close()
        worker.run()
    }

    private fun words() = arrayOf("芝士".toCharArray(), "知识".toCharArray())
    private class Scorer : CandidateScorer {
        var closes = 0
        var borrowed: CharArray? = null
        var during: () -> Unit = {}
        override fun score(context: CharArray, words: Array<CharArray>, cancelled: () -> Boolean): FloatArray {
            borrowed = context
            during()
            return floatArrayOf(-4f, -1f)
        }
        override fun close() { closes++ }
    }
    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { if (stopped) throw RejectedExecutionException(); tasks.add(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
