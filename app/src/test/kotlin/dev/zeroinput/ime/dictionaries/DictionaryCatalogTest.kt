package dev.zeroinput.ime.dictionaries

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI

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
        assertFalse(rows.first().category)
        assertEquals("qq", rows.first().format)
        assertTrue(rows[1].category)
        assertTrue(rows[1].address.endsWith("page=2"))
    }

    @Test fun sogouRetainsNestedCategoryAndPageRoutes() {
        val rows = catalog.parseWebsite(DictionarySource.SOGOU, "https://pinyin.sogou.com/dict/cate/index/1", """
            <a href="/dict/cate/index/2">child</a>
            <a href="/dict/cate/index/1/default/2">next</a>
            <a href="/dict/detail/index/154343">words</a>
        """.trimIndent())
        assertEquals(3, rows.size)
        assertTrue(rows[1].category)
        assertEquals("154343", rows.first().id)
        assertEquals("1", rows.last().id)
    }

    @Test fun qqHomepageAndCategoryKeepDownloadsAheadOfLargeNavigationLists() {
        val links = (1..220).joinToString("") { """<a href="/list?cate_id=$it">Category $it</a>""" }
        val html = links + """<a href="/detail?dict_id=s332">Words</a>
            <a href="/detail?dict_id=s332&amp;cate_id=3">Same words</a>"""
        for (page in listOf(DictionarySource.QQ.website, "${DictionarySource.QQ.website}list?cate_id=3")) {
            val rows = catalog.parseWebsite(DictionarySource.QQ, page, html)
            assertEquals("s332", rows.first().id)
            assertEquals(1, rows.count { !it.category })
            assertTrue(rows.none { it.address == page })
        }
    }

    @Test fun sogouHomepageKeepsFeaturedDownloadsAndRejectsUnexpectedRoutes() {
        val rows = catalog.parseWebsite(DictionarySource.SOGOU, DictionarySource.SOGOU.website, """
            <a href="/dict/cate/index/1">Nature</a>
            <a href="/dict/cate/index/1/download/2">Next</a>
            <a href="/dict/detail/index/15206/?rf=dictindex">Words</a>
            <a href="/dict/detail/index/15206">Same words</a>
            <a href="/dict/detail/index/15206/unexpected/8">Other route</a>
        """.trimIndent())
        assertEquals(listOf("15206", "1", "1"), rows.map { it.id })
        assertFalse(rows.first().category)
        assertEquals("/dict/cate/index/1/download/2", URI(rows.last().address).path)
    }

    @Test fun qqDownloadUsesTheSelectedIdAndUpgradesOnlyItsOfficialLegacyFileRedirect() {
        val detail = "https://cdict.qq.pinyin.cn/detail?dict_id=s332"
        val item = CatalogItem("s332", "fixture", detail, format = "qq")
        val endpoint = URI(catalog.resolve(DictionarySource.QQ, item))
        assertEquals("https://cdict.qq.pinyin.cn/download?dict_id=s332", endpoint.toString())
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve(DictionarySource.QQ, item.copy(id = "s333"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve(DictionarySource.QQ, item.copy(format = "scel"))
        }
        val path = "/dict/package/73/aa5141ef807c87363acee63c96d8b9fc.qcel"
        val advertised = "http://dl.qqpy.sogou.com$path"
        assertEquals("https://dl.qqpy.sogou.com$path", DictionaryDownloadTransport.redirect(endpoint, advertised).toString())
        for (address in listOf("http://elsewhere.example$path", "http://dl.qqpy.sogou.com/other.qcel",
            "$advertised?extra=1", "$advertised#fragment", "http://user@dl.qqpy.sogou.com$path")) {
            assertThrows(IllegalArgumentException::class.java) { DictionaryDownloadTransport.redirect(endpoint, address) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DictionaryDownloadTransport.redirect(URI(detail), advertised)
        }
    }

    @Test fun sogouResolvesOnlyTheSelectedFileAndKeepsTheRequiredNameParameter() {
        val item = CatalogItem("15206", "fixture", "https://pinyin.sogou.com/dict/detail/index/15206", format = "scel")
        val unrelated = """
            <a href="/d/dict/download_cell.php?id=71952&amp;name=Other">Recommended</a>
            <a href="/d/dict/download_cell.php?id=15206">Missing name</a>
            <a href="https://github.com/d/dict/download_cell.php?id=15206&amp;name=Other">Other host</a>
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) { catalog.resolveSogou(item) { unrelated } }
        val resolved = catalog.resolveSogou(item) { unrelated +
            """<a href="/d/dict/download_cell.php?id=15206&amp;name=公开 词库&amp;f=detail">Download</a>""" }
        assertEquals("https://pinyin.sogou.com/d/dict/download_cell.php?id=15206&name=%E5%85%AC%E5%BC%80%20%E8%AF%8D%E5%BA%93&f=detail",
            resolved)
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

    @Test fun zhwikiDoesNotAdvertiseBinaryOrOverLimitAssetsForImport() {
        val assets = org.json.JSONArray()
        for ((name, size) in listOf("text.dict.yaml" to 400L, "binary.dict" to 200L, "oversized.dict.yaml" to 195530063L)) {
            assets.put(org.json.JSONObject().put("name", name).put("size", size)
                .put("browser_download_url", "https://github.com/a/$name"))
        }
        val release = org.json.JSONObject().put("tag_name", "test").put("assets", assets)
        assertEquals(listOf("text.dict.yaml"), catalog.parseZhwiki(release.toString()).map { it.title })
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
