package dev.zeroinput.ime.dictionaries

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DictionaryCatalogTest {
    private val catalog = DictionaryCatalog(File("unused"), DictionaryDownloadTransport())

    @Test fun qqRetainsCategoryPaginationAndDetailsWithoutExternalLinks() {
        val rows = catalog.parseWebsite(DictionarySource.QQ, "https://cdict.qq.pinyin.cn/list?cate_id=167", """
            <a href="/list?cate_id=167&page=2">2</a>
            <a href="/list?cate_id=168">category</a>
            <a href="/detail?dict_id=s332">words</a>
            <a href="https://evil.example/detail?dict_id=x">external</a>
            <a href="javascript:alert(1)">script</a>
        """.trimIndent())
        assertEquals(3, rows.size)
        assertTrue(rows.first().category)
        assertTrue(rows.first().address.endsWith("page=2"))
        assertFalse(rows.last().category)
        assertEquals("qq", rows.last().format)
    }

    @Test fun sogouRetainsNestedCategoryAndPageRoutes() {
        val rows = catalog.parseWebsite(DictionarySource.SOGOU, "https://pinyin.sogou.com/dict/cate/index/1", """
            <a href="/dict/cate/index/2">child</a>
            <a href="/dict/cate/index/1/default/2">next</a>
            <a href="/dict/detail/index/154343">words</a>
        """.trimIndent())
        assertEquals(3, rows.size)
        assertTrue(rows[1].category)
        assertEquals("154343", rows.last().id)
    }

    @Test fun transportRejectsUnsafeDestinationsIncludingRedirectTargets() {
        for (url in listOf("http://pinyin.sogou.com/dict/", "https://evil.example/",
            "https://github.com.evil.example/a", "https://u:p@github.com/a",
            "https://github.com:8443/a", "https://github.com/a#secret")) {
            assertThrows(IllegalArgumentException::class.java) { DictionaryDownloadTransport.validate(url) }
        }
        assertEquals("github.com", DictionaryDownloadTransport.validate("https://github.com/a").host)
    }

    @Test fun zhwikiRejectsUnsafeAssetAddressesBeforeDisplayingRows() {
        for (url in listOf("http://github.com/a", "https://evil.example/a",
            "https://u:p@github.com/a", "https://github.com:8443/a", "https://github.com/a#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { catalog.parseZhwiki(release(url)) }
        }
        val address = "https://github.com/felixonmars/fcitx5-pinyin-zhwiki/releases/download/test/zhwiki.dict.yaml"
        assertEquals(address, catalog.parseZhwiki(release(address)).single().address)
    }

    @Test fun directCatalogResolutionRevalidatesAllGithubSources() {
        for (source in listOf(DictionarySource.ZHWIKI, DictionarySource.ICE, DictionarySource.WANXIANG)) {
            assertThrows(IllegalArgumentException::class.java) {
                catalog.resolve(source, CatalogItem("fixture", "fixture", "https://evil.example/a"))
            }
        }
    }

    @Test fun githubTreeBoundsOnlySupportedRowsAndRejectsAnOversizedCatalog() {
        val entries = org.json.JSONArray()
        repeat(1025) { entries.put(org.json.JSONObject().put("path", "other/$it.txt").put("type", "blob")) }
        repeat(1024) { entries.put(org.json.JSONObject().put("path", "dicts/$it.dict.yaml").put("type", "blob")) }
        val response = org.json.JSONObject().put("tree", entries)
        val sha = "a".repeat(40)
        assertEquals(1024, catalog.parseGithubTree(response, "amzxyz/rime-wanxiang", "dicts/", sha).size)
        entries.put(org.json.JSONObject().put("path", "dicts/overflow.dict.yaml").put("type", "blob"))
        assertThrows(IllegalArgumentException::class.java) {
            catalog.parseGithubTree(response, "amzxyz/rime-wanxiang", "dicts/", sha)
        }
    }

    @Test fun zhwikiRejectsMoreThan1024SupportedAssets() {
        val assets = org.json.JSONArray()
        repeat(1024) { assets.put(org.json.JSONObject().put("name", "$it.dict.yaml")
            .put("browser_download_url", "https://github.com/a/$it")) }
        val release = org.json.JSONObject().put("tag_name", "test").put("assets", assets)
        assertEquals(1024, catalog.parseZhwiki(release.toString()).size)
        assets.put(org.json.JSONObject().put("name", "overflow.dict.yaml").put("browser_download_url", "https://github.com/a"))
        assertThrows(IllegalArgumentException::class.java) { catalog.parseZhwiki(release.toString()) }
    }

    @Test fun websiteCatalogsUseTheSame1024RowLimit() {
        val html = (0 until 1024).joinToString("") { "<a href='/detail?dict_id=$it'>fixture $it</a>" }
        val address = "https://cdict.qq.pinyin.cn/list?cate_id=1"
        assertEquals(1024, catalog.parseWebsite(DictionarySource.QQ, address, html).size)
        assertThrows(IllegalArgumentException::class.java) {
            catalog.parseWebsite(DictionarySource.QQ, address, html + "<a href='/detail?dict_id=overflow'>overflow</a>")
        }
    }

    private fun release(address: String) = org.json.JSONObject().apply {
        put("tag_name", "test")
        put("assets", org.json.JSONArray().put(org.json.JSONObject().apply {
            put("name", "zhwiki.dict.yaml")
            put("browser_download_url", address)
        }))
    }.toString()
}
