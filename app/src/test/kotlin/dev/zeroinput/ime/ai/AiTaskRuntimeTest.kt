package dev.zeroinput.ime.ai

import android.app.Application
import android.os.Looper
import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiTaskRuntimeTest {
    @Test fun disablingNotificationsCancelsAnOutstandingBackgroundRequestWithoutWaitingForItsResponse() = fixture { runtime, provider, _, app ->
        runtime.attach("owner", Observer())
        assertTrue(start(runtime, "owner", background = true))
        val token = shadowOf(app).nextStartedService.getStringExtra(AiRunNotifications.TOKEN)!!
        runtime.serviceStarted(token); drain(); runtime.leave("owner", false)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(runtime.running); assertTrue(runtime.events.isEmpty()); assertEquals(1, provider.cancellations)
        provider.reply(Result.success(AiToolResponse("Late public response"))); drain()
        assertTrue(runtime.events.isEmpty())
    }
    @Test fun defaultTaskStopsOnLeavingAndLateModelCallbacksCannotRestoreIt() = fixture { runtime, provider, _, _ ->
        val observer = Observer(); runtime.attach("first", observer)
        assertTrue(start(runtime, "first")); drain()
        runtime.leave("first", false); drain()
        assertFalse(runtime.running); assertNull(runtime.snapshot)
        provider.reply(Result.success(AiToolResponse("Late public response"))); drain()
        assertTrue(runtime.events.isEmpty())
    }
    @Test fun backgroundApprovalWaitsForCurrentForegroundOwnerAndOldOwnersCannotDetachIt() = fixture { runtime, provider, _, app ->
        val observer = Observer(); runtime.attach("first", observer)
        assertTrue(start(runtime, "first", background = true))
        val token = shadowOf(app).nextStartedService.getStringExtra(AiRunNotifications.TOKEN)!!
        assertEquals(0, provider.requests)
        assertTrue(runtime.serviceStarted(token)); drain(); assertEquals(1, provider.requests)
        runtime.leave("first", false)
        provider.reply(Result.success(AiToolResponse("", AiToolCall("call", "files_read", """{"file":"file1","offset":0,"length":4}""")))); drain()
        val approval = runtime.events.last().approval!!
        assertFalse(runtime.approve("first", approval, true))
        runtime.attach("replacement", observer); runtime.destroy("first")
        assertTrue(runtime.approve("replacement", approval, false)); drain()
        assertEquals(2, provider.requests)
        runtime.stop("stale-public-token"); assertTrue(runtime.running)
        runtime.stop(token); drain(); assertFalse(runtime.running)
    }
    @Test fun timeoutRevocationAndServiceLossClearStateWithoutRestart() = fixture { runtime, provider, data, app ->
        val observer = Observer(); runtime.attach("owner", observer)
        assertTrue(start(runtime, "owner", background = true))
        val token = shadowOf(app).nextStartedService.getStringExtra(AiRunNotifications.TOKEN)!!
        runtime.serviceStarted(token); drain(); runtime.leave("owner", false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        assertFalse(runtime.running); assertFalse(runtime.validOpen(token)); assertTrue(runtime.events.isEmpty())
        runtime.attach("owner", observer); assertTrue(start(runtime, "owner")); drain()
        data.invalidate(); provider.reply(Result.success(AiToolResponse("Expired public response"))); drain()
        assertFalse(runtime.running); assertTrue(runtime.events.isEmpty())
        runtime.attach("owner", observer); assertTrue(start(runtime, "owner", background = true))
        val next = shadowOf(app).nextStartedService.getStringExtra(AiRunNotifications.TOKEN)!!
        runtime.serviceStarted(next); runtime.serviceLost(next); drain()
        assertFalse(runtime.running); assertTrue(runtime.events.isEmpty())
    }
    @Test fun backgroundNeedsNotificationsAndRejectsSaveAs() = fixture { runtime, _, _, _ ->
        runtime.attach("owner", Observer())
        assertFalse(runtime.start("owner", request(), executors(), emptyList(), "Public model", null, false, true))
        assertFalse(runtime.start("owner", AiToolRequest("Public", AiFileTools.definitions(emptyList())),
            mapOf(AiFileTools.SAVE to Executor), emptyList(), "Public model", null, true, true))
        assertFalse(runtime.running)
    }
    private fun start(runtime: AiTaskRuntime, owner: String, background: Boolean = false) =
        runtime.start(owner, request(), executors(), listOf("file1: Public.txt"), "Public model", null, background, background)
    private fun request() = AiToolRequest("Public task", AiFileTools.definitions(listOf("file1")).filter { it.name == AiFileTools.READ })
    private fun executors() = mapOf(AiFileTools.READ to Executor)
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun fixture(test: (AiTaskRuntime, Provider, AiDataGeneration, Application) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val profile = AiProviderProfile("fixture", "Public", "https://example.test/v1", "public-key", listOf("public-model"), "public-model")
        val config = AiConfiguration(enabled = true, networkAllowed = true, providers = listOf(profile), selectedProviderId = profile.id)
        val provider = Provider(); val data = AiDataGeneration()
        val runtime = AiTaskRuntime(app, { config }, { true }, data, AiDataGeneration(), provider)
        try { test(runtime, provider, data, app) } finally { runtime.close(); drain() }
    }
    private class Observer : AiTaskObserver {
        override fun event(value: AiToolRunEvent) = Unit
        override fun cleared(wasRunning: Boolean) = Unit
    }
    private class Provider : AiToolProvider {
        var requests = 0; var cancellations = 0; lateinit var reply: (Result<AiToolResponse>) -> Unit
        override fun request(request: AiToolRequest, completed: (Result<AiToolResponse>) -> Unit): AiRequestHandle {
            requests++; reply = completed; return object : AiRequestHandle { override fun cancel() { cancellations++ } }
        }
    }
    private object Executor : AiToolExecutor {
        override fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit): AiRequestHandle = error("No unapproved execution")
    }
}
