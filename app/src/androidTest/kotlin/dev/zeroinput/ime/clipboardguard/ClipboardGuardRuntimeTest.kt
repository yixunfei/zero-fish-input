package dev.zeroinput.ime.clipboardguard

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.concurrency.BoundedExecutors
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardGuardRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun defaultOffNeverCreatesPlatformAccessAndDisabledSettingsSurviveRecreation() = withFixture { fixture ->
        fixture.runtime.attachIme()
        fixture.configure(ClipboardGuardOptions(keyboardReminder = true, authenticate = true))
        await { fixture.runtime.state.options.authenticate }
        assertEquals(0, fixture.created.get())
        assertEquals(ClipboardGuardStatus.OFF, fixture.runtime.state.status)
        val restored = ClipboardGuardPreferences(fixture.preferencesFile).options
        assertTrue(restored.keyboardReminder && restored.authenticate)
        assertFalse(restored.listening || restored.notificationReminder)
        assertEquals(ClipboardClearMode.NONE, restored.clearMode)
    }

    @Test
    fun selectedImeWaitsForServiceAttachmentBeforeMonitoring() = withFixture { fixture ->
        fixture.port.stamp = 42
        fixture.configure(ClipboardGuardOptions(listening = true, clearMode = ClipboardClearMode.AUTOMATIC))
        await { fixture.runtime.state.status == ClipboardGuardStatus.UNAVAILABLE }
        assertEquals(0, fixture.created.get())
        assertEquals(null, fixture.port.listener.get())
        fixture.runtime.attachIme()
        await { fixture.runtime.state.status == ClipboardGuardStatus.CLEARED && fixture.port.listener.get() != null }
        assertEquals(1, fixture.port.clears.get())
        assertEquals(null, fixture.runtime.state.ticket)
    }

    @Test
    fun nonDefaultForegroundRequestClearsWithoutStartingAMonitor() = withFixture { fixture ->
        fixture.selected.set(false)
        fixture.port.stamp = 42
        fixture.configure(ClipboardGuardOptions(listening = true))
        await { fixture.runtime.state.status == ClipboardGuardStatus.NOT_DEFAULT }
        val inspected = AtomicReference<ClipboardGuardState>()
        fixture.runtime.inspectCurrent({ true }, inspected::set)
        await { inspected.get()?.ticket != null }
        assertEquals(null, fixture.port.listener.get())
        val outcome = AtomicReference<ClipboardClearResult>()
        fixture.runtime.clear(checkNotNull(inspected.get().ticket).id, null, { true }, outcome::set)
        await { outcome.get() != null }
        assertEquals(ClipboardClearResult.CLEARED, outcome.get())
        assertEquals(1, fixture.port.clears.get())
        assertEquals(null, fixture.port.listener.get())
    }

    @Test
    fun automaticProtectionClearsExistingItemWhileAnotherKeyboardIsSelectedAndPageIsFocused() = withFixture { fixture ->
        fixture.selected.set(false)
        fixture.port.stamp = 42
        fixture.configure(ClipboardGuardOptions(listening = true, clearMode = ClipboardClearMode.AUTOMATIC))
        await { fixture.runtime.state.status == ClipboardGuardStatus.NOT_DEFAULT }
        val inspected = AtomicReference<ClipboardGuardState>()
        fixture.runtime.inspectCurrent({ true }, inspected::set, automatic = true)
        await { inspected.get() != null }
        assertEquals(ClipboardGuardStatus.CLEARED, inspected.get().status)
        assertEquals(1, fixture.port.clears.get())
        assertEquals(null, fixture.port.listener.get())
    }

    @Test
    fun disablingWhileAnEventIsBlockedCancelsAutomaticClearAndRemovesTheListener() = withFixture { fixture ->
        fixture.start(ClipboardClearMode.AUTOMATIC)
        val block = ReadBlock()
        fixture.port.block.set(block)
        fixture.port.stamp = 1
        fixture.port.notifyChanged()
        assertTrue(block.entered.await(5, TimeUnit.SECONDS))
        fixture.configure(ClipboardGuardOptions())
        block.release.countDown()
        await { fixture.runtime.state.status == ClipboardGuardStatus.OFF && fixture.port.listener.get() == null }
        assertEquals(0, fixture.port.clears.get())
        assertEquals(null, fixture.runtime.state.ticket)
    }

    @Test
    fun changingDefaultImeRejectsAClearEvenBeforeItsObserverRuns() = withFixture { fixture ->
        fixture.start(ClipboardClearMode.CONFIRM)
        fixture.port.stamp = 1
        fixture.port.notifyChanged()
        await { fixture.runtime.state.ticket != null }
        val ticket = checkNotNull(fixture.runtime.state.ticket)
        fixture.selected.set(false)
        val outcome = AtomicReference<ClipboardClearResult>()
        fixture.runtime.clear(ticket.id, null, { true }, outcome::set)
        await { outcome.get() != null }
        assertEquals(ClipboardClearResult.EXPIRED, outcome.get())
        assertEquals(0, fixture.port.clears.get())
        fixture.port.notifyChanged()
        await { fixture.runtime.state.status == ClipboardGuardStatus.NOT_DEFAULT }
        assertEquals(null, fixture.port.listener.get())
    }

    @Test
    fun detachingImeInvalidatesQueuedClearAndRuntimeCanBeReattached() = withFixture { fixture ->
        fixture.start(ClipboardClearMode.CONFIRM)
        fixture.port.stamp = 1
        fixture.port.notifyChanged()
        await { fixture.runtime.state.ticket != null }
        val ticket = checkNotNull(fixture.runtime.state.ticket)
        val block = ReadBlock()
        fixture.port.block.set(block)
        fixture.port.notifyChanged()
        assertTrue(block.entered.await(5, TimeUnit.SECONDS))
        val outcome = AtomicReference<ClipboardClearResult>()
        fixture.runtime.clear(ticket.id, null, { true }, outcome::set)
        fixture.runtime.detachIme()
        block.release.countDown()
        await { outcome.get() != null && fixture.runtime.state.status == ClipboardGuardStatus.UNAVAILABLE }
        assertEquals(ClipboardClearResult.EXPIRED, outcome.get())
        assertEquals(0, fixture.port.clears.get())
        fixture.runtime.attachIme()
        await { fixture.runtime.state.status == ClipboardGuardStatus.WAITING && fixture.port.listener.get() != null }
        assertEquals(null, fixture.runtime.state.ticket)
    }

    @Test
    fun closeReleasesMonitoringAndRejectsFurtherActions() = withFixture { fixture ->
        fixture.start(ClipboardClearMode.CONFIRM)
        fixture.runtime.close()
        await { fixture.port.listener.get() == null }
        val outcome = AtomicReference<ClipboardClearResult>()
        fixture.runtime.clear("expired", null, { true }, outcome::set)
        await { outcome.get() != null }
        assertEquals(ClipboardClearResult.EXPIRED, outcome.get())
        fixture.runtime.attachIme()
        assertEquals(null, fixture.port.listener.get())
    }

    @Test
    fun closeReservesCleanupWhenItsWorkerQueueIsFull() = withFixture { fixture ->
        fixture.start(ClipboardClearMode.CONFIRM)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.worker.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val staleTaskRan = AtomicBoolean()
        fixture.worker.execute { staleTaskRan.set(true) }
        try {
            fixture.runtime.close()
            assertTrue(fixture.worker.isShutdown)
        } finally { release.countDown() }
        await { fixture.port.listener.get() == null }
        assertFalse(staleTaskRan.get())
    }

    @Test
    fun foregroundInspectionWithoutAnImeServiceTargetsExistingContentWithoutAutomaticCleanup() = withFixture { fixture ->
        fixture.port.stamp = 42
        fixture.configure(ClipboardGuardOptions(listening = true, clearMode = ClipboardClearMode.AUTOMATIC))
        await { fixture.runtime.state.status == ClipboardGuardStatus.UNAVAILABLE }
        val inspected = AtomicReference<ClipboardGuardState>()
        fixture.runtime.inspectCurrent({ true }, inspected::set)
        await { inspected.get() != null }
        val ticket = checkNotNull(inspected.get().ticket)
        assertTrue(ticket.userRequested)
        assertEquals(0, fixture.port.clears.get())
        assertEquals(null, fixture.port.listener.get())
        val outcome = AtomicReference<ClipboardClearResult>()
        fixture.runtime.clear(ticket.id, null, { true }, outcome::set)
        await { outcome.get() != null }
        assertEquals(ClipboardClearResult.CLEARED, outcome.get())
    }

    @Test
    fun failedPlatformCreationCompletesInspectionAndAllowsRetry() = withFixture { fixture ->
        fixture.configure(ClipboardGuardOptions(listening = true))
        await { fixture.runtime.state.status == ClipboardGuardStatus.UNAVAILABLE }
        fixture.failCreation.set(true)
        val inspected = AtomicReference<ClipboardGuardState>()
        fixture.runtime.inspectCurrent({ true }, inspected::set)
        await { inspected.get() != null && fixture.runtime.state.status == ClipboardGuardStatus.FAILED }
        assertEquals(ClipboardGuardStatus.FAILED, inspected.get().status)
        assertEquals(null, inspected.get().ticket)
        fixture.failCreation.set(false)
        fixture.runtime.retryMonitoring()
        await { fixture.runtime.state.status == ClipboardGuardStatus.UNAVAILABLE }
        fixture.port.stamp = 42
        inspected.set(null)
        fixture.runtime.inspectCurrent({ true }, inspected::set)
        await { inspected.get()?.ticket != null }
        assertEquals(0, fixture.port.clears.get())
    }

    @Test
    fun cancellingAnInspectionWhileThePlatformIsBlockedCannotIssueATicket() = withFixture { fixture ->
        fixture.start(ClipboardClearMode.CONFIRM)
        fixture.port.stamp = 42
        val active = AtomicBoolean(true)
        val block = ReadBlock()
        fixture.port.block.set(block)
        val inspected = AtomicReference<ClipboardGuardState>()
        fixture.runtime.inspectCurrent(active::get, inspected::set)
        assertTrue(block.entered.await(5, TimeUnit.SECONDS))
        active.set(false)
        block.release.countDown()
        await { inspected.get() != null }
        assertEquals(null, inspected.get().ticket)
        assertEquals(null, fixture.runtime.state.ticket)
        assertEquals(0, fixture.port.clears.get())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally {
            fixture.port.block.getAndSet(null)?.release?.countDown()
            fixture.runtime.close()
            await { fixture.port.listener.get() == null }
            instrumentation.targetContext.deleteSharedPreferences(fixture.name)
        }
    }

    private inner class Fixture {
        val name = "guard-test-${UUID.randomUUID()}"
        val preferencesFile = instrumentation.targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val preferences = ClipboardGuardPreferences(preferencesFile)
        val port = FakeClipboard()
        val selected = AtomicBoolean(true)
        val created = AtomicInteger()
        val failCreation = AtomicBoolean()
        val worker = BoundedExecutors.singleThread("guard-runtime-test", 1)
        val runtime = ClipboardGuardRuntime(instrumentation.targetContext, preferences,
            createClipboard = { check(!failCreation.get()); created.incrementAndGet(); port }, isDefaultIme = selected::get,
            worker = worker)

        fun configure(options: ClipboardGuardOptions) {
            instrumentation.runOnMainSync { preferences.options = options }
        }

        fun start(mode: ClipboardClearMode) {
            configure(ClipboardGuardOptions(listening = true, clearMode = mode))
            runtime.attachIme()
            await { runtime.state.status == ClipboardGuardStatus.WAITING && port.listener.get() != null }
        }
    }

    private class ReadBlock {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class FakeClipboard : SystemClipboardPort {
        @Volatile var stamp: Long? = null
        val clears = AtomicInteger()
        val listener = AtomicReference<(() -> Unit)?>()
        val block = AtomicReference<ReadBlock?>()

        override fun timestamp(): Long? {
            block.getAndSet(null)?.let {
                it.entered.countDown()
                check(it.release.await(5, TimeUnit.SECONDS))
            }
            return stamp
        }

        override fun clear() { clears.incrementAndGet(); stamp = null }
        override fun listen(onChanged: () -> Unit): AutoCloseable {
            listener.set(onChanged)
            return AutoCloseable { listener.compareAndSet(onChanged, null) }
        }
        fun notifyChanged() { listener.get()?.invoke() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("Guard state did not settle", condition())
    }
}
