package dev.zeroinput.ime.ai

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.userdata.AiConnectionRepository
import java.net.InetAddress
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiRemoteToolsPlatformTest {
    @Test fun dedicatedConnectionFixtureUsesKeystoreAndDeletesItsOwnKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AiConnectionRepository(EncryptedFileStore(context, "connections-platform-fixture.bin", "zeroinput.test.connections"))
        try {
            repository.clear()
            repository.write(0, AiConnectionLibrary(connections = listOf(AiConnection("public", "Public", AiConnectionKind.MCP, "https://example.com/mcp", "public-token")))) { true }
            assertEquals("public-token", repository.read().connections.single().token)
            repository.clear(); assertTrue(repository.read().connections.isEmpty())
        } finally { repository.clear() }
    }
    @Test fun actualAndroidClientRejectsMixedDnsBeforeDispatch() {
        var calls = 0
        val adapter = AiRemoteHttpsTransport {
            calls++; listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))
        }
        val cancel = AiRemoteCancellation { true }
        assertTrue(runCatching { adapter.mcp(AiConnection("public", "Public", AiConnectionKind.MCP, "https://example.com/mcp", "public-token"), "{}", null, cancel) }.isFailure)
        assertEquals(1, calls); assertFalse(cancel.dispatched.get())
    }
    @Test fun closingPrivateConnectionManagerRevokesQueuedReadsAndCannotConnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AiToolTaskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AiToolTaskActivity
        var reads = 0; var network = 0; val work = ArrayDeque<Runnable>()
        val repository = AiConnectionRepository(object : dev.zeroinput.security.EncryptedStore {
            override fun read(): ByteArray? { reads++; return null }
            override fun write(plaintext: ByteArray) = Unit
            override fun delete(deleteKey: Boolean) = Unit
        })
        val transport = object : AiRemoteTransport {
            override fun mcp(connection: AiConnection, body: String, session: String?, cancellation: AiRemoteCancellation): AiRemoteReply { network++; error("No network expected") }
            override fun search(connection: AiConnection, query: String, cancellation: AiRemoteCancellation): AiRemoteReply { network++; error("No network expected") }
        }
        try {
            instrumentation.runOnMainSync {
                val review = AiConnectionsDialog(activity, repository, Executor { work.add(it) }, Executor { work.add(it) },
                    AiDataGeneration(), AiDataGeneration(), Handler(Looper.getMainLooper()), { true }, { true }, {}, { _, _ -> false }, transport)
                review.show(); review.close()
                while (work.isNotEmpty()) work.removeFirst().run()
                assertEquals(0, reads); assertEquals(0, network)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
