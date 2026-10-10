package dev.zeroinput.ime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.dictionaries.DictionaryDownloadTransport
import dev.zeroinput.languagepack.PublicResourceStore
import dev.zeroinput.model.RobertaMiniScorer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in live download of fixed public data; no editor or personal data is accessed. */
class MiniDownloadTest {
    @Test fun explicitDownloadInstallsReviewedMiniAndRemovalDisablesIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("resourceNetworkCheck") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "mini-network-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = directory
        }
        try {
            val store = PublicResourceStore(context)
            val pack = store.bundledCatalog().single { it.id == "mini-int8" }
            val archive = File(directory, "download.zip")
            DictionaryDownloadTransport().download(pack.url, archive, pack.bytes, timeoutMillis = 180_000)
            store.install(pack, archive)
            RobertaMiniScorer(store).use { scorer ->
                val scores = scorer.score("我买了一件新".toCharArray(),
                    arrayOf("衣服".toCharArray(), "依附".toCharArray())) { false }
                assertArrayEquals(floatArrayOf(-2.2595852f, -14.597835f), requireNotNull(scores), .025f)
            }
            store.remove("mini-int8")
            assertNull(store.acquire("mini-int8"))
        } finally { directory.deleteRecursively() }
    }
}
