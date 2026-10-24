package dev.zeroinput.ime.dictionaries

import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.dictionary.importer.CellDictionaryParser
import dev.zeroinput.engine.dictionary.importer.PublicDictionaryEntry
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device-side checks use bounded local fixtures so a connected test run does not
 * depend on GitHub or a public dictionary site being reachable. Live downloads
 * remain an explicit opt-in operation and are covered by the transport tests.
 */
class PublicDictionaryCatalogDeviceTest {
    private val catalog = DictionaryCatalog(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        DictionaryDownloadTransport(),
    )

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
        RimeTextDictionaryParser().parse(
            "---\nname: fixture\ncolumns: [text, code, weight]\n...\n测试\tce shi\t12\n"
                .byteInputStream(),
            ::add,
        )
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
