package dev.zeroinput.ime.textstego

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageCredentialMode
import dev.zeroinput.security.message.MessageKdf
import dev.zeroinput.security.message.MessageKeyFile
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageStage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class StegoKeyFilePlatformTest {
    @Test fun generatedStandaloneFileCanBeReopenedAsAKeyFactorAndCannotBeOverwritten() {
        val file = File.createTempFile("message-public-export-", ".zikey", Device.context.cacheDir)
        try {
            val resolver = ContentResolver.wrap(PublicProvider(file))
            assertFalse(StegoKeyFileWrite().write(resolver, URI) { false })
            assertEquals(0, file.length())
            assertTrue(StegoKeyFileWrite().write(resolver, URI) { true })
            assertEquals(32, file.length())
            val factor = requireNotNull(StegoKeyFileRead().read(resolver, URI) { true })
            try {
                assertFalse(StegoKeyFileWrite().write(resolver, URI) { true })
                assertArrayEquals(factor, StegoKeyFileRead().read(resolver, URI) { true })
                val sample = "办公记录\r\n只用于公开回归。".toCharArray()
                val stages = listOf(MessageStage(MessageCipher.CAMELLIA, 192), MessageStage(MessageCipher.XCHACHA20))
                val profile = StegoWordingProfile.PROSE_WINDOW
                MessageCredential(MessageCredentialMode.KEY_FILE, charArrayOf(), factor).use { key ->
                    val carrier = StegoWordingCodec.seal(Device.context.assets, profile, sample, key, stages, MessageKdf.NONE, false) { true }
                    val opened = StegoWordingCodec.open(Device.context.assets, profile, carrier.text, key, { true }) {
                        it.mode == MessageCredentialMode.KEY_FILE && it.kdf == MessageKdf.NONE
                    }
                    assertArrayEquals(sample, opened)
                }
            } finally { factor.fill(0) }
        } finally { assertTrue(file.delete()) }
    }

    @Test fun aSlowProviderCanCompleteAfterFiveSecondsWithoutPersistingAccess() {
        val bytes = ByteArray(128) { it.toByte() }
        val file = File.createTempFile("message-public-slow-", ".bin", Device.context.cacheDir)
        try {
            file.writeBytes(bytes)
            val provider = PublicProvider(file).apply { delayMillis = 5500 }
            val end = android.os.SystemClock.elapsedRealtime() + 120_000
            assertArrayEquals(MessageKeyFile.factor(ByteArrayInputStream(bytes)),
                StegoKeyFileRead().read(ContentResolver.wrap(provider), URI) { android.os.SystemClock.elapsedRealtime() < end })
        } finally { assertTrue(file.delete()) }
    }

    @Test fun temporaryProviderReadChecksActualBoundsAndNeverPersistsAUriGrant() {
        val bytes = ByteArray(128) { it.toByte() }
        val publicFile = File.createTempFile("message-public-", ".bin", Device.context.cacheDir)
        val grants = Device.context.contentResolver.persistedUriPermissions.map { it.uri }
        try {
            val provider = PublicProvider(publicFile)
            val resolver = ContentResolver.wrap(provider)
            publicFile.writeBytes(bytes)
            assertArrayEquals(MessageKeyFile.factor(ByteArrayInputStream(bytes)), StegoKeyFileRead().read(resolver, URI) { true })
            for (size in listOf(0, 31, 1_048_577)) {
                publicFile.writeBytes(ByteArray(size))
                assertNull(StegoKeyFileRead().read(resolver, URI) { true })
            }
            val opens = provider.opens
            assertNull(StegoKeyFileRead().read(resolver, Uri.parse("file:///public-fixture")) { true })
            assertNull(StegoKeyFileRead().apply { close() }.read(resolver, URI) { true })
            assertEquals(opens, provider.opens)
            assertEquals(grants, Device.context.contentResolver.persistedUriPermissions.map { it.uri })
        } finally { assertTrue(publicFile.delete()) }
    }

    @Test fun cancellingProviderOpenRejectsItsLateResultAndReleasesWorker() {
        val publicFile = File.createTempFile("message-public-", ".bin", Device.context.cacheDir)
        val executor = Executors.newSingleThreadExecutor()
        val provider = PublicProvider(publicFile).apply { block = true }
        val read = StegoKeyFileRead()
        try {
            val task = executor.submit<ByteArray?> { read.read(ContentResolver.wrap(provider), URI) { true } }
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS))
            read.close()
            assertTrue(provider.cancelled.await(2, TimeUnit.SECONDS))
            assertNull(task.get(2, TimeUnit.SECONDS))
            assertEquals(1, executor.submit<Int> { 1 }.get(2, TimeUnit.SECONDS))
        } finally {
            read.close(); provider.cancelled.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); assertTrue(publicFile.delete())
        }
    }

    private class PublicProvider(private val file: File) : ContentProvider() {
        var opens = 0
        var block = false
        var delayMillis = 0L
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            opens++
            if (delayMillis > 0) android.os.SystemClock.sleep(delayMillis)
            if (block) {
                signal?.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                signal?.throwIfCanceled()
            }
            return ParcelFileDescriptor.open(file, if (mode == "rw") ParcelFileDescriptor.MODE_READ_WRITE
                else ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun openFile(uri: Uri, mode: String) = openFile(uri, mode, null)
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }
    companion object { private val URI = Uri.parse("content://dev.zeroinput.public-fixture/message-key") }
}
