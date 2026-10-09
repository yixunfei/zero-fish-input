package dev.zeroinput.ime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.languagepack.PublicDictionaryStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class PublicDictionaryStoreTest {
    @Test fun failedOrCancelledReplacementRetainsPublishedDataAndSignalsOnlyCommits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "public-store-test")
        assertFalse(root.exists())
        val fixture = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        val store = PublicDictionaryStore(fixture)
        var publications = 0
        store.onPublished = { publications++ }
        fun install(text: String) = store.install("fixture", "fixture", "ICE", "test",
            text.byteInputStream(), RimeTextDictionaryParser())
        try {
            val good = install("---\nname: fixture\n...\n你好\tni hao\t1\n你好\tni hao\t10\n")
            assertEquals(1L, good.entries)
            assertEquals(1, publications)
            assertThrows(IllegalArgumentException::class.java) { install("---\nname: fixture\n...\n") }
            assertEquals(good, store.list().single())
            Thread.currentThread().interrupt()
            try {
                assertThrows(CancellationException::class.java) {
                    install("---\nname: fixture\n...\n测试\tce shi\t1\n")
                }
            } finally { Thread.interrupted() }
            assertEquals(1, publications)
            assertEquals(good, store.list().single())
            store.setEnabled("fixture", false)
            assertTrue(store.enabledFiles().isEmpty())
            store.remove("fixture")
            assertTrue(store.list().isEmpty())
            assertEquals(3, publications)
        } finally { root.deleteRecursively() }
    }
}
