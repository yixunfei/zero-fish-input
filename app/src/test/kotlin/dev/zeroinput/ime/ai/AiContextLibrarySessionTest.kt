package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiContextLibraryRepository
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiContextLibrarySessionTest {
    @Test fun queuedSaveCannotResurrectClearedDataOrOutliveItsSurface() {
        for (close in listOf(true, false)) {
            val worker = Queue(); val ui = Queue(); val data = AiDataGeneration(); val repo = repository()
            val results = mutableListOf<Result<AiContextLibrary>>()
            val session = AiContextLibrarySession(repo, worker, data, { ui.execute(it); true }, results::add)
            session.write(0, AiContextLibrary(personas = listOf(AiPersona("id", "Public", "", "Concise"))))
            if (close) session.close() else { data.invalidate(); repo.clear() }
            worker.drain(); ui.drain()
            assertTrue(results.isEmpty()); assertTrue(repo.read().personas.isEmpty())
        }
    }

    @Test fun lateReadIsDiscardedAndExecutorRejectionIsVisible() {
        val worker = Queue(); val ui = Queue(); val results = mutableListOf<Result<AiContextLibrary>>()
        val session = AiContextLibrarySession(repository(), worker, AiDataGeneration(), { ui.execute(it); true }, results::add)
        session.read(); worker.drain(); session.close(); ui.drain()
        assertTrue(results.isEmpty())
        AiContextLibrarySession(repository(), Executor { throw RejectedExecutionException() }, AiDataGeneration(), { it(); true }, results::add).read()
        assertTrue(results.single().isFailure)
    }

    private fun repository() = AiContextLibraryRepository(object : EncryptedStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { bytes = null }
    })
    private class Queue : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
