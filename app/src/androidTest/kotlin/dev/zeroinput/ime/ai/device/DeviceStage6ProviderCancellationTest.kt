package dev.zeroinput.ime.ai.device

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ai.api.DeviceStage6Operation
import dev.zeroinput.ai.api.DeviceStage6OperationRequest
import dev.zeroinput.ai.api.DeviceStage6ResultStatus
import dev.zeroinput.ai.api.DeviceStage6Target
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the Android resolver cancellation transport with synthetic, target-scoped data. */
@SdkSuppress(minSdkVersion = 29)
class DeviceStage6ProviderCancellationTest {
    @Test fun cancellingEachProviderReadReleasesTheSingleWorkerForTheNextOperation() {
        for (capability in listOf(DeviceStage6Capability.SMS, DeviceStage6Capability.TELEPHONY,
            DeviceStage6Capability.CONTACTS, DeviceStage6Capability.CALENDAR)) {
            val provider = BlockingProvider()
            val platform = platform(provider)
            val cancellation = DeviceStage6Cancellation()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val first = worker.submit<dev.zeroinput.ai.api.DeviceStage6Result> {
                    platform.execute(request(capability), 0, { true }, cancellation)
                }
                assertTrue("Provider query started: $capability", provider.entered.await(5, TimeUnit.SECONDS))
                cancellation.cancel()
                assertTrue("Provider received cancellation: $capability", provider.cancelled.await(2, TimeUnit.SECONDS))
                assertEquals(DeviceStage6ResultStatus.EXPIRED, first.get(3, TimeUnit.SECONDS).status)
                provider.block = false
                val next = worker.submit<dev.zeroinput.ai.api.DeviceStage6Result> {
                    platform.execute(request(capability), 0, { true }, DeviceStage6Cancellation())
                }
                assertEquals(DeviceStage6ResultStatus.FAILED, next.get(3, TimeUnit.SECONDS).status)
            } finally {
                provider.unblock.countDown()
                worker.shutdownNow()
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun alreadyCancelledOperationNeverEntersTheProvider() {
        val provider = BlockingProvider().apply { block = false }
        val cancellation = DeviceStage6Cancellation().apply { cancel() }
        val result = platform(provider).execute(request(DeviceStage6Capability.SMS), 0, { true }, cancellation)
        assertEquals(DeviceStage6ResultStatus.EXPIRED, result.status)
        assertEquals(1L, provider.entered.count)
    }

    @Test fun completedOperationUnregistersItsSignal() {
        val provider = BlockingProvider().apply { block = false }
        val cancellation = DeviceStage6Cancellation()
        platform(provider).execute(request(DeviceStage6Capability.SMS), 0, { true }, cancellation)
        val signal = checkNotNull(provider.lastSignal)
        cancellation.cancel()
        assertFalse(signal.isCanceled)
    }

    @Test fun cancellingAnInFlightWriteDoesNotRetryOrStartTheFallbackInsert() {
        val provider = BlockingProvider().apply { block = false; contactWrite = true }
        val cancellation = DeviceStage6Cancellation()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val request = DeviceStage6OperationRequest("write-test", 1, "dev.zeroinput.fixture",
                DeviceStage6Capability.CONTACTS, DeviceStage6Operation.UPDATE,
                DeviceStage6Target(DeviceStage6Capability.CONTACTS, "fixture"), mapOf("new_display_name" to "renamed"))
            val result = worker.submit<dev.zeroinput.ai.api.DeviceStage6Result> {
                platform(provider).execute(request, 0, { true }, cancellation)
            }
            assertTrue(provider.writeEntered.await(5, TimeUnit.SECONDS))
            cancellation.cancel()
            assertFalse("Android writes remain owned by the worker until they return", result.isDone)
            provider.finishWrite.countDown()
            assertEquals(DeviceStage6ResultStatus.EXPIRED, result.get(3, TimeUnit.SECONDS).status)
            assertEquals(1, provider.writes)
            // The fixture throws if the adapter attempts the fallback insert.
        } finally {
            provider.finishWrite.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun platform(provider: ContentProvider): AndroidDeviceStage6Platform {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ContentResolver.wrap(provider)
        return AndroidDeviceStage6Platform(object : ContextWrapper(target) {
            override fun getContentResolver(): ContentResolver = resolver
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
        })
    }

    private fun request(capability: DeviceStage6Capability) = DeviceStage6OperationRequest(
        "provider-test", 1, "dev.zeroinput.fixture", capability, DeviceStage6Operation.READ,
        DeviceStage6Target(capability, "fixture"), mapOf("start_epoch_ms" to "1000"),
    )

    private class BlockingProvider : ContentProvider() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        var contactWrite = false
        @Volatile var writes = 0
        @Volatile var block = true
        @Volatile var lastSignal: CancellationSignal? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
            lastSignal = cancellationSignal
            cancellationSignal?.setOnCancelListener { cancelled.countDown(); unblock.countDown() }
            entered.countDown()
            if (block) check(unblock.await(10, TimeUnit.SECONDS)) { "Fixture query timed out" }
            cancellationSignal?.throwIfCanceled()
            return MatrixCursor(projection ?: arrayOf("_id")).apply {
                if (contactWrite) {
                    if (uri.path == "/contacts") addRow(arrayOf<Any>(1L, "fixture"))
                    else if (uri.path == "/raw_contacts") addRow(arrayOf(2L))
                }
            }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            query(uri, projection, selection, selectionArgs, sortOrder, null)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected fixture write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            check(contactWrite)
            writes++
            writeEntered.countDown()
            check(finishWrite.await(10, TimeUnit.SECONDS))
            return 0
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected fixture write")
    }
}
