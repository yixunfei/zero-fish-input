package dev.zeroinput.ime.ai

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AiAttachmentReaderPlatformTest {
    @Test fun skillDocumentReaderRejectsRevocationInvalidEncodingAndOversizedOrNonTextFiles() {
        withDocument("text/markdown", "---\nname: Public\n---\nPublic instructions".toByteArray()) { resolver ->
            val text = AiDocumentTextReader.read(resolver, URI, android.os.CancellationSignal(), 16_384) { true }
            assertEquals("Public", AiSkillDocument.parse(text).name)
            assertThrows(IllegalStateException::class.java) {
                AiDocumentTextReader.read(resolver, URI, android.os.CancellationSignal(), 16_384) { false }
            }
            val cancelled = android.os.CancellationSignal().apply { cancel() }
            assertThrows(android.os.OperationCanceledException::class.java) {
                AiDocumentTextReader.read(resolver, URI, cancelled, 16_384) { true }
            }
            assertThrows(IllegalArgumentException::class.java) {
                AiDocumentTextReader.read(resolver, Uri.parse("file:///private"), android.os.CancellationSignal(), 16_384) { true }
            }
        }
        for ((mime, bytes) in listOf("text/plain" to ByteArray(16_385) { 65 },
            "text/plain" to byteArrayOf(0xc0.toByte()), "text/plain" to byteArrayOf(0),
            "application/zip" to "Public".toByteArray())) {
            withDocument(mime, bytes) { resolver ->
                assertThrows(Exception::class.java) {
                    AiDocumentTextReader.read(resolver, URI, android.os.CancellationSignal(), 16_384) { true }
                }
            }
        }
    }

    @Test fun documentBytesAreBoundedEvenWhenProviderReportsAnIncorrectSize() {
        withDocument("text/plain", "public document".toByteArray()) { resolver ->
            val attachment = AiAttachmentReader.read(resolver, URI)
            assertEquals("public document", String(attachment.bytes))
            assertEquals("fixture.txt", attachment.displayName)
            attachment.bytes.fill(0)
        }
        withDocument("image/png", ByteArray(1_000_001)) { resolver ->
            assertThrows(IllegalArgumentException::class.java) { AiAttachmentReader.read(resolver, URI) }
        }
        withDocument("text/plain", byteArrayOf(0xc0.toByte())) { resolver ->
            assertThrows(Exception::class.java) { AiAttachmentReader.read(resolver, URI) }
        }
        withDocument("application/zip", byteArrayOf(1)) { resolver ->
            assertThrows(IllegalArgumentException::class.java) { AiAttachmentReader.read(resolver, URI) }
        }
    }

    private fun withDocument(mime: String, bytes: ByteArray, test: (ContentResolver) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("ai-public-fixture", ".bin", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = mime
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                    selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                        .apply { addRow(arrayOf("fixture.txt", 1)) }
                override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
                    AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                        0, AssetFileDescriptor.UNKNOWN_LENGTH)
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            }
            test(ContentResolver.wrap(provider))
        } finally { file.delete() }
    }

    companion object { private val URI = Uri.parse("content://fixture/document") }
}
