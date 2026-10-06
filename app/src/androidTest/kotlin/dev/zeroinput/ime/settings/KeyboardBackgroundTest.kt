package dev.zeroinput.ime.settings

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.security.AesGcmKeyStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class KeyboardBackgroundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rasterImportIsBoundedAndInvalidContentFailsClosed() = fixture { f ->
        f.file.writeBytes(publicImage(2500, 1500))
        val normalized = BackgroundImageCodec.read(f.resolver, URI, CancellationSignal())
        val bitmap = BackgroundImageCodec.decode(normalized)
        try { assertTrue(bitmap.width <= 1280); assertTrue(bitmap.height <= 1280) }
        finally { bitmap.recycle(); normalized.fill(0) }
        f.file.writeBytes(ByteArray(BackgroundImageCodec.MAX_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { BackgroundImageCodec.read(f.resolver, URI, CancellationSignal()) }
        f.file.writeText("not an image")
        assertThrows(IllegalArgumentException::class.java) { BackgroundImageCodec.read(f.resolver, URI, CancellationSignal()) }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundImageCodec.read(f.resolver, Uri.parse("file:///private/image.png"), CancellationSignal())
        }
        val cancelled = CancellationSignal().apply { cancel() }
        assertThrows(android.os.OperationCanceledException::class.java) { BackgroundImageCodec.read(f.resolver, URI, cancelled) }
    }

    @Test fun successfulImageIsEncryptedReloadableAndInvalidReplacementPreservesIt() = fixture { f ->
        f.file.writeBytes(publicImage())
        assertTrue(import(f))
        val saved = f.settings.keyboardAppearance
        assertEquals(KeyboardBackground.IMAGE, saved.background)
        assertTrue(saved.imageRevision.isNotEmpty())
        val encrypted = f.imageFile(saved).readBytes()
        assertFalse(encrypted.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte()))
        val bitmap = load(f, saved)
        assertNotNull(bitmap)
        assertEquals(Color.BLUE, bitmap?.getPixel(2, 2))
        bitmap?.recycle()
        f.file.writeText("invalid replacement")
        assertFalse(import(f))
        assertEquals(saved, f.settings.keyboardAppearance)
        val loadedAgain = load(f, saved)
        assertNotNull(loadedAgain)
        loadedAgain?.recycle()
    }

    @Test fun corruptedCiphertextAndMissingKeyReturnNoImage() = fixture { f ->
        f.file.writeBytes(publicImage())
        assertTrue(import(f))
        val saved = f.settings.keyboardAppearance
        val original = f.imageFile(saved).readBytes()
        f.imageFile(saved).writeBytes(original.copyOf(12))
        assertNull(load(f, saved))
        f.imageFile(saved).writeBytes(original)
        AesGcmKeyStore(f.alias).deleteKey()
        assertNull(load(f, saved))
    }

    @Test fun removingWhileImportIsBlockedCannotResurrectImageOrNotifyCancelledOwner() = fixture { f ->
        f.file.writeBytes(publicImage())
        f.block = CountDownLatch(1)
        val removed = CountDownLatch(1)
        var importCallbacks = 0
        onMain { f.store.import(URI) { importCallbacks++ } }
        assertTrue(f.opened.await(5, TimeUnit.SECONDS))
        onMain { f.store.remove { assertTrue(it); removed.countDown() } }
        f.block?.countDown()
        assertTrue(removed.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(0, importCallbacks)
        assertEquals("", f.settings.keyboardAppearance.imageRevision)
        assertEquals(KeyboardBackground.SOLID, f.settings.keyboardAppearance.background)
        assertTrue(File(f.directory, "encrypted").listFiles().orEmpty().isEmpty())
        assertFalse(AesGcmKeyStore(f.alias).hasKey())
    }

    @Test fun detachedImageLoadDoesNotDeliverToAnOldView() = fixture { f ->
        f.file.writeBytes(publicImage())
        assertTrue(import(f))
        var callbacks = 0
        onMain { f.store.load(f.settings.keyboardAppearance) { callbacks++; it?.recycle() }.close() }
        val barrier = CountDownLatch(1)
        onMain { f.store.remove { barrier.countDown() }.close() }
        // Removal is durable even after its UI owner closes.
        val drain = load(f, KeyboardAppearance())
        assertNull(drain)
        instrumentation.waitForIdleSync()
        assertEquals(0, callbacks)
        assertTrue(File(f.directory, "encrypted").listFiles().orEmpty().isEmpty())
    }

    private fun import(f: Fixture): Boolean {
        var result = false
        val done = CountDownLatch(1)
        onMain { f.store.import(URI) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun load(f: Fixture, value: KeyboardAppearance): Bitmap? {
        var result: Bitmap? = null
        val done = CountDownLatch(1)
        onMain { f.store.load(value) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture(instrumentation.targetContext)
        try { test(f) } finally {
            f.block?.countDown()
            val done = CountDownLatch(1)
            onMain { f.store.remove { done.countDown() } }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            onMain { f.store.close() }
            f.file.delete()
            f.directory.deleteRecursively()
        }
    }

    private fun publicImage(width: Int = 80, height: Int = 40): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private class Fixture(base: Context) {
        private val id = UUID.randomUUID().toString()
        val directory = File(base.noBackupFilesDir, "appearance-test-$id").apply { mkdirs() }
        val alias = "zeroinput.appearance-test.$id"
        val file = File.createTempFile("public-background", ".png", base.cacheDir)
        val opened = CountDownLatch(1)
        @Volatile var block: CountDownLatch? = null
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$id", mode)
        }
        val settings = SettingsRepository(context)
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
                opened.countDown()
                check(block?.await(10, TimeUnit.SECONDS) != false)
                return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                    0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val store = KeyboardBackgroundStore(context, settings, resolver, alias)
        fun imageFile(value: KeyboardAppearance) = File(directory, "encrypted/keyboard-background-${value.imageRevision}.bin")
    }

    companion object { private val URI = Uri.parse("content://fixture/background") }
}
