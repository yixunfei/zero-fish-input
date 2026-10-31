package dev.zeroinput.ime.auth

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import dev.zeroinput.security.AuthenticationGrant
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthenticationBrokerDeadlineTest {
    @Test
    fun completionAtDeadlineRejectsFreshGrantWhileTimeoutCallbackIsStillPending() = withRequest { context, results ->
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(30))
        AuthenticationBroker.complete(context.requestId, AuthenticationGrant.afterSuccessfulSystemAuthentication())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<AuthenticationGrant?>(null), results)
    }

    @Test
    fun successQueuedBeforeDeadlineIsRevokedWhenDeliveryOccursAfterDeadline() = withRequest { context, results ->
        val grant = AuthenticationGrant.afterSuccessfulSystemAuthentication()
        AuthenticationBroker.complete(context.requestId, grant)
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(31))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<AuthenticationGrant?>(null), results)
        AuthenticationBroker.complete(context.requestId, grant)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
    }

    @Test
    fun deliveryJustBeforeDeadlinePreservesFreshGrantAndCompletesOnlyOnce() = withRequest { context, results ->
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(29_999))
        val grant = AuthenticationGrant.afterSuccessfulSystemAuthentication()
        AuthenticationBroker.complete(context.requestId, grant)
        AuthenticationBroker.complete(context.requestId, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(grant), results)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(1, results.size)
    }

    @Test
    fun normalTimeoutDeliversCancellationOnceAndIgnoresLateSuccess() = withRequest { context, results ->
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(listOf<AuthenticationGrant?>(null), results)
        AuthenticationBroker.complete(context.requestId, AuthenticationGrant.afterSuccessfulSystemAuthentication())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
    }

    private fun withRequest(test: (CapturingContext, MutableList<AuthenticationGrant?>) -> Unit) {
        val context = CapturingContext()
        val results = mutableListOf<AuthenticationGrant?>()
        AuthenticationBroker.requestCancellable(context, callback = results::add).use {
            test(context, results)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class CapturingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var requestId: String

        override fun startActivity(intent: Intent) {
            requestId = checkNotNull(intent.getStringExtra(SecureClipboardUnlockActivity.EXTRA_REQUEST_ID))
        }
    }
}
