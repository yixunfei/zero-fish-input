package dev.zeroinput.ime.dictionaries

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.dictionary.importer.CellDictionaryParser
import dev.zeroinput.engine.dictionary.importer.PublicDictionaryEntry
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.languagepack.BuiltInDictionaryStore
import dev.zeroinput.languagepack.PublicDictionaryStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The default device checks use local fixtures. The live import requires an
 * explicit test-runner argument and uses a separate public dictionary store.
 */
class PublicDictionaryCatalogDeviceTest {
    private val catalog = DictionaryCatalog(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        DictionaryDownloadTransport(),
    )

    @Test
    fun nativeDownloadImportsEachSupportedFormatIntoThePublicStore() {
        val rimeAddress = "https://raw.githubusercontent.com/iDvel/rime-ice/fixture/cn_dicts/fixture.dict.yaml"
        val responses = mapOf(
            "https://cdict.qq.pinyin.cn/download?dict_id=s332" to
                cellFixture(CellDictionaryParser.Source.QQ),
            sogou.address to
                """<a href="/d/dict/download_cell.php?id=15206&amp;name=Fixture&amp;f=detail">download</a>""".toByteArray(),
            "https://pinyin.sogou.com/d/dict/download_cell.php?id=15206&name=Fixture&f=detail" to
                cellFixture(CellDictionaryParser.Source.SOGOU),
            rimeAddress to rimeFixture.toByteArray(),
        )
        val requested = mutableListOf<String>()
        val transport = object : DictionaryFileDownload {
            override fun download(address: String, target: File, limit: Long, timeoutMillis: Long,
                progress: (Long, Long) -> Unit) {
                val data = responses.getValue(address)
                assertTrue(data.size <= limit)
                requested += address
                target.writeBytes(data)
            }
        }
        withDownloads(transport) { downloads, _, store ->
            downloads.install(DictionarySource.QQ, qq) { _, _ -> }
            downloads.install(DictionarySource.SOGOU, sogou) { _, _ -> }
            downloads.install(DictionarySource.ICE, CatalogItem("fixture", "fixture", rimeAddress)) { _, _ -> }
            assertEquals(setOf("QQ", "SOGOU", "ICE"), store.list().map { it.source }.toSet())
            assertTrue(store.list().all { it.enabled && it.entries == 1L })
            assertEquals(responses.keys.toList(), requested)
        }
    }

    @Test fun liveQqHomepageDownloadImports() = withLiveDownload(DictionarySource.QQ) {
        it.list(DictionarySource.QQ).first { item -> !item.category && item.id == qq.id }
    }

    @Test fun liveSogouCategoryDownloadImports() = withLiveDownload(DictionarySource.SOGOU) {
        val category = it.list(DictionarySource.SOGOU).first { item -> item.category && item.id == "1" }
        it.list(DictionarySource.SOGOU, category.address).first { item -> !item.category && item.id == sogou.id }
    }

    @Test fun liveZhwikiFileDownloadsAndImports() = withLiveDownload(DictionarySource.ZHWIKI) {
        it.list(DictionarySource.ZHWIKI).first { item -> item.id.startsWith("web-slang-") }
    }

    private fun withLiveDownload(source: DictionarySource, choose: (DictionaryCatalog) -> CatalogItem) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePublicDictionary") == "true")
        withDownloads(DictionaryDownloadTransport()) { downloads, liveCatalog, store ->
            downloads.install(source, choose(liveCatalog)) { _, _ -> }
            val imported = store.list().single { it.source == source.name }
            assertTrue(imported.enabled && imported.entries > 0)
        }
    }

    private fun withDownloads(transport: DictionaryFileDownload,
        work: (DictionaryDownloads, DictionaryCatalog, PublicDictionaryStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("public-import-", ".test", context.cacheDir)
        check(root.delete() && root.mkdir())
        try {
            val isolated = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir() = root
            }
            val store = PublicDictionaryStore(isolated)
            val liveCatalog = DictionaryCatalog(isolated.cacheDir, transport)
            val downloads = DictionaryDownloads(isolated, transport, liveCatalog, store, BuiltInDictionaryStore(isolated, store))
            work(downloads, liveCatalog, store)
        } finally { root.deleteRecursively() }
    }

    private val qq = CatalogItem("s332", "成语俗语大全",
        "https://cdict.qq.pinyin.cn/detail?dict_id=s332", format = "qq")
    private val sogou = CatalogItem("15206", "动物词汇大全",
        "https://pinyin.sogou.com/dict/detail/index/15206", format = "scel")
    private val rimeFixture = "---\nname: fixture\ncolumns: [text, code, weight]\n...\n测试\tce shi\t12\n"


    @Test
    fun publicCatalogFixturesResolveAndParseRows() {
        val commit = "a".repeat(40)
        val tree = JSONArray().apply {
            put(JSONObject().put("path", "dicts/fixture.dict.yaml").put("type", "blob"))
            put(JSONObject().put("path", "dicts/ignored.txt").put("type", "blob"))
        }
        val rows = catalog.parseGithubTree(
            JSONObject().put("truncated", false).put("tree", tree),
            "amzxyz/rime-wanxiang", "dicts/", commit,
        )
        assertEquals(1, rows.size)
        assertEquals(
            "https://raw.githubusercontent.com/amzxyz/rime-wanxiang/$commit/dicts/fixture.dict.yaml",
            rows.single().address,
        )

        val zhwikiAddress = "https://github.com/felixonmars/fcitx5-pinyin-zhwiki/releases/download/test/zhwiki.dict.yaml"
        val zhwikiRows = catalog.parseZhwiki(zhwikiRelease(zhwikiAddress))
        assertEquals(zhwikiAddress, zhwikiRows.single().address)

        val entries = parseRimeFixture()
        assertEquals(listOf(PublicDictionaryEntry("测试", "ce shi", 12)), entries)
    }

    @Test
    fun nativeCatalogFixturesExposeCategoriesAndParseEntries() {
        val qqRows = catalog.parseWebsite(
            DictionarySource.QQ,
            "https://cdict.qq.pinyin.cn/",
            "<a href=\"/list?cate_id=167\">QQ category</a>",
        )
        val qqCategory = qqRows.single()
        assertTrue(qqCategory.category)
        val qqDetails = catalog.parseWebsite(
            DictionarySource.QQ,
            qqCategory.address,
            "<a href=\"/detail?dict_id=s332\">QQ words</a>",
        )
        assertEquals("qq", qqDetails.single().format)

        val sogouRows = catalog.parseWebsite(
            DictionarySource.SOGOU,
            "https://pinyin.sogou.com/dict/",
            "<a href=\"/dict/cate/index/1\">Sogou category</a>",
        )
        val sogouCategory = sogouRows.single()
        assertTrue(sogouCategory.category)
        val sogouDetails = catalog.parseWebsite(
            DictionarySource.SOGOU,
            sogouCategory.address,
            "<a href=\"/dict/detail/index/154343\">Sogou words</a>",
        )
        assertEquals("154343", sogouDetails.single().id)
        assertEquals("scel", sogouDetails.single().format)

        assertEquals(1, parseCellFixture(CellDictionaryParser.Source.QQ).size)
        assertEquals(1, parseCellFixture(CellDictionaryParser.Source.SOGOU).size)
    }

    private fun parseRimeFixture(): List<PublicDictionaryEntry> = buildList {
        RimeTextDictionaryParser().parse(rimeFixture.byteInputStream(), ::add)
    }

    private fun parseCellFixture(source: CellDictionaryParser.Source): List<PublicDictionaryEntry> = buildList {
        CellDictionaryParser(source).parse(ByteArrayInputStream(cellFixture(source)), ::add)
    }

    private fun cellFixture(source: CellDictionaryParser.Source): ByteArray {
        val word = "你".toByteArray(Charsets.UTF_16LE)
        val buffer = ByteBuffer.allocate(0x2628 + 14).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x1540)
        buffer.put(if (source == CellDictionaryParser.Source.QQ) {
            byteArrayOf(0x44, 0x43, 0x53, 1)
        } else {
            byteArrayOf(0x44, 0x43, 0x53, 2)
        })
        buffer.position(0x1540)
        buffer.putInt(720)
        repeat(720) { index ->
            val syllable = when (index) {
                0 -> "ni"
                719 -> "aa"
                else -> "a"
            }.toByteArray(Charsets.UTF_16LE)
            buffer.putShort((index + 1).toShort())
            buffer.putShort(syllable.size.toShort())
            buffer.put(syllable)
        }
        buffer.position(0x2628)
        buffer.putShort(1)
        buffer.putShort(2)
        buffer.putShort(1)
        buffer.putShort(word.size.toShort())
        buffer.put(word)
        buffer.putShort(2)
        buffer.putShort(1)
        return buffer.array().copyOf(buffer.position())
    }

    private fun zhwikiRelease(address: String): String = JSONObject().apply {
        put("tag_name", "test")
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", "zhwiki.dict.yaml")
            put("browser_download_url", address)
        }))
    }.toString()
}
