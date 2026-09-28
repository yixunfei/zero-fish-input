package dev.zeroinput.ime.auth

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.security.AuthenticationGrant
import org.junit.Assert.*
import org.junit.Test

class AuthenticationBrokerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun cancellationRevokesASuccessAlreadyQueuedForDelivery() {
        val results = mutableListOf<AuthenticationGrant?>()
        val context = CapturingContext()
        onMain {
            val request = AuthenticationBroker.requestCancellable(context, callback = results::add)
            AuthenticationBroker.complete(context.requestId, AuthenticationGrant.afterSuccessfulSystemAuthentication())
            request.close()
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(listOf<AuthenticationGrant?>(null), results) }
    }

    @Test fun duplicateCompletionAndCancellationDeliverOnlyOnce() {
        val results = mutableListOf<AuthenticationGrant?>()
        val context = CapturingContext()
        lateinit var request: AuthenticationBroker.RequestHandle
        val grant = AuthenticationGrant.afterSuccessfulSystemAuthentication()
        onMain {
            request = AuthenticationBroker.requestCancellable(context, callback = results::add)
            AuthenticationBroker.complete(context.requestId, grant)
            AuthenticationBroker.complete(context.requestId, null)
        }
        instrumentation.waitForIdleSync()
        onMain {
            request.close()
            AuthenticationBroker.complete(context.requestId, grant)
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(listOf(grant), results) }
    }

    @Test fun cancellationBeforeAuthenticationRejectsLateSuccess() {
        val results = mutableListOf<AuthenticationGrant?>()
        val context = CapturingContext()
        onMain {
            val request = AuthenticationBroker.requestCancellable(context, callback = results::add)
            request.close()
            request.close()
            AuthenticationBroker.complete(context.requestId, AuthenticationGrant.afterSuccessfulSystemAuthentication())
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(listOf<AuthenticationGrant?>(null), results) }
    }

    private inner class CapturingContext : ContextWrapper(instrumentation.targetContext) {
        lateinit var requestId: String
        override fun startActivity(intent: Intent) {
            requestId = checkNotNull(intent.getStringExtra(SecureClipboardUnlockActivity.EXTRA_REQUEST_ID))
        }
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
