package dev.zeroinput.ime.dictionaries

import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.dictionary.importer.CellDictionaryParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicitly invoked integration check against public catalogs; no editor data is used. */
class PublicDictionaryCatalogDeviceTest {
    @Test fun githubSourcesResolvePinnedFilesAndParseTheirPublicRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = DictionaryDownloadTransport()
        val catalog = DictionaryCatalog(context.cacheDir, transport)
        for (source in listOf(DictionarySource.WANXIANG, DictionarySource.ICE, DictionarySource.ZHWIKI)) {
            val items = catalog.list(source)
            assertTrue(items.isNotEmpty())
            val item = items.first()
            val file = File.createTempFile("github-catalog-test-", ".tmp", context.cacheDir)
            try {
                transport.download(catalog.resolve(source, item), file, 128L * 1024 * 1024)
                var count = 0
                file.inputStream().use {
                    dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser().parse(it) { count++ }
                }
                assertTrue(count > 0)
            } finally { file.delete() }
        }
    }

    @Test fun qqAndSogouExposeNativeCategoriesAndDownloadParseableEntries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = DictionaryDownloadTransport()
        val catalog = DictionaryCatalog(context.cacheDir, transport)
        for (source in listOf(DictionarySource.QQ, DictionarySource.SOGOU)) {
            val categories = catalog.list(source)
            assertTrue(categories.any { it.category })
            var rows = catalog.list(source, categories.first { it.category }.address)
            if (rows.none { !it.category }) rows = catalog.list(source, rows.first { it.category }.address)
            val item = rows.first { !it.category }
            val download = catalog.resolve(source, item)
            val file = File.createTempFile("catalog-test-", ".tmp", context.cacheDir)
            try {
                transport.download(download, file, 32L * 1024 * 1024)
                var count = 0
                val parser = CellDictionaryParser(if (source == DictionarySource.QQ)
                    CellDictionaryParser.Source.QQ else CellDictionaryParser.Source.SOGOU)
                file.inputStream().use { parser.parse(it) { count++ } }
                assertTrue(count > 0)
            } finally { file.delete() }
        }
    }
}
