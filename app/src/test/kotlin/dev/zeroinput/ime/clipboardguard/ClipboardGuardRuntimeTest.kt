package dev.zeroinput.ime.clipboardguard

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClipboardGuardRuntimeTest {
    @Test fun registrationThatSucceedsBeforeThrowingIsStillUnregistered() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val preferences = ClipboardGuardPreferences(context).apply { options = ClipboardGuardOptions(listening = true) }
        val registered = AtomicInteger()
        val unregistered = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val runtime = ClipboardGuardRuntime(context, preferences, isDefaultIme = { false }, worker = worker,
            registerDefaultImeObserver = { registered.incrementAndGet(); error("Uncertain Binder result") },
            unregisterDefaultImeObserver = { unregistered.incrementAndGet() })
        try {
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(1, registered.get())
            assertEquals(1, unregistered.get())
            assertEquals(ClipboardGuardStatus.FAILED, runtime.state.status)
        } finally {
            runtime.close()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
        assertEquals(1, unregistered.get())
    }
}
