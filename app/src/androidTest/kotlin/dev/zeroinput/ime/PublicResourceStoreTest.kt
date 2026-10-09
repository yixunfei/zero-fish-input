package dev.zeroinput.ime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.languagepack.PublicResourcePack
import dev.zeroinput.languagepack.PublicResourceStore
import dev.zeroinput.languagepack.ResourceFileSpec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PublicResourceStoreTest {
    @Test fun atomicInstallRejectsDamageAndKeepsLeasedFilesUntilReadersClose() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "resource-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = directory
        }
        try {
            val store = PublicResourceStore(context)
            var signals = 0
            store.onPublished = { signals++ }
            val archive = File(directory, "test.zip")
            val data = "public test model".toByteArray()
            val pack = pack(archive, "rime/wanxiang-lts-zh-hans.gram", data)
            store.install(pack, archive)
            val lease = requireNotNull(store.acquire("wanxiang-lts"))
            val file = File(lease.files.values.single().path)
            assertArrayEquals(data, file.readBytes())
            val damaged = pack(archive, "../outside.gram", data)
            assertThrows(IllegalArgumentException::class.java) { store.install(damaged, archive) }
            assertEquals(1, signals)
            assertArrayEquals(data, file.readBytes())
            store.setEnabled("wanxiang-lts", false)
            assertNull(store.acquire("wanxiang-lts"))
            store.remove("wanxiang-lts")
            assertTrue(file.exists())
            lease.close()
            store.remove("wanxiang-lts")
            assertFalse(file.exists())
            assertFalse(File(directory.parentFile, "outside.gram").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun checksumFailureAndCancellationDoNotPublish() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "resource-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = directory
        }
        try {
            val store = PublicResourceStore(context)
            val archive = File(directory, "test.zip")
            val pack = pack(archive, "rime/wanxiang-lts-zh-hans.gram", byteArrayOf(1, 2, 3))
            assertThrows(IllegalArgumentException::class.java) { store.install(pack.copy(sha256 = "a".repeat(64)), archive) }
            Thread.currentThread().interrupt()
            try { assertThrows(java.util.concurrent.CancellationException::class.java) { store.install(pack, archive) } }
            finally { Thread.interrupted() }
            assertEquals(0L, store.revision)
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingDuplicateOversizedAndPerFileHashFailuresKeepPreviousPublication() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "resource-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = directory
        }
        try {
            val store = PublicResourceStore(context)
            val archive = File(directory, "test.zip")
            val name = "rime/wanxiang-lts-zh-hans.gram"
            val data = byteArrayOf(1, 2, 3)
            val original = pack(archive, name, data)
            store.install(original, archive)
            val spec = original.files.single()
            for (changed in listOf(spec.copy(bytes = 2), spec.copy(sha256 = "b".repeat(64)))) {
                assertThrows(IllegalArgumentException::class.java) {
                    store.install(original.copy(files = listOf(changed)), archive)
                }
            }
            ZipOutputStream(archive.outputStream()).use { }
            assertThrows(IllegalArgumentException::class.java) {
                store.install(original.copy(bytes = archive.length(), sha256 = hash(archive.readBytes())), archive)
            }
            // Build two different equal-length names, then replace both local
            // and central metadata to exercise a duplicate-name ZIP fixture.
            val alias = "rime/wanxiang-lts-zh-hant.gram"
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (entry in listOf(name, alias)) {
                    zip.putNextEntry(ZipEntry(entry)); zip.write(data); zip.closeEntry()
                }
            }
            archive.writeBytes(String(archive.readBytes(), Charsets.ISO_8859_1)
                .replace(alias, name).toByteArray(Charsets.ISO_8859_1))
            assertThrows(IllegalArgumentException::class.java) {
                store.install(original.copy(bytes = archive.length(), sha256 = hash(archive.readBytes())), archive)
            }
            assertEquals(1L, store.revision)
            requireNotNull(store.acquire("wanxiang-lts")).use {
                assertArrayEquals(data, File(it.files.values.single().path).readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    private fun pack(archive: File, name: String, data: ByteArray): PublicResourcePack {
        ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry(name)); it.write(data); it.closeEntry() }
        return PublicResourcePack("wanxiang-lts", "0123456789abcdef", "CC-BY-4.0",
            "https://github.com/yixunfei/zero-fish-input/releases/download/resources-0123456789abcdef/wanxiang-lts-0123456789abcdef.zip",
            archive.length(), hash(archive.readBytes()), listOf(ResourceFileSpec("rime/wanxiang-lts-zh-hans.gram", data.size.toLong(), hash(data))))
    }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
