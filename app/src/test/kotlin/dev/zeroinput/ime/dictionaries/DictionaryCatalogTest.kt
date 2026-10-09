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
}
