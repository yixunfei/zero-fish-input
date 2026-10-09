package dev.zeroinput.ime.dictionaries

import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal enum class DictionarySource { WANXIANG, ICE, ZHWIKI, QQ, SOGOU }
internal data class CatalogItem(
    val id: String, val title: String, val address: String,
    val category: Boolean = false, val format: String = "rime", val version: String = "",
)

/** Converts bounded public catalogs into native rows. HTML scripts are never executed. */
internal class DictionaryCatalog(private val cache: File, private val transport: DictionaryDownloadTransport) {
    fun list(source: DictionarySource, page: String? = null): List<CatalogItem> = when (source) {
        DictionarySource.QQ -> website(source, page ?: "https://cdict.qq.pinyin.cn/")
        DictionarySource.SOGOU -> website(source, page ?: "https://pinyin.sogou.com/dict/")
        DictionarySource.ICE -> githubTree("iDvel/rime-ice", "cn_dicts/")
        DictionarySource.WANXIANG -> githubTree("amzxyz/rime-wanxiang", "dicts/")
        DictionarySource.ZHWIKI -> zhwiki()
    }

    fun resolve(source: DictionarySource, item: CatalogItem): String {
        if (source != DictionarySource.QQ && source != DictionarySource.SOGOU) return item.address
        val html = read(item.address)
        val document = Jsoup.parse(html, item.address)
        val link = document.select("a[href]").firstOrNull {
            val path = it.attr("href")
            if (source == DictionarySource.QQ) path.startsWith("/download?dict_id=")
            else path.contains("/d/dict/download_cell.php?")
        } ?: error("Source has no supported download")
        return secureResolve(item.address, link.attr("href"))
    }

    private fun website(source: DictionarySource, address: String): List<CatalogItem> {
        return parseWebsite(source, address, read(address))
    }

    internal fun parseWebsite(source: DictionarySource, address: String, html: String): List<CatalogItem> {
        val document = Jsoup.parse(html, address)
        val root = URI(address).path in listOf("/", "/dict/")
        val output = LinkedHashMap<String, CatalogItem>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href")
            val title = link.text().trim()
            if (title.isEmpty() || title.length > 160) continue
            val uri = runCatching { URI(secureResolve(address, href)) }.getOrNull() ?: continue
            if (uri.host != URI(address).host) continue
            val category = if (source == DictionarySource.QQ) uri.path == "/list"
                else uri.path.startsWith("/dict/cate/index/")
            val detail = if (source == DictionarySource.QQ) uri.path == "/detail"
                else uri.path.startsWith("/dict/detail/index/")
            if (!category && (!detail || root)) continue
            val url = uri.toASCIIString()
            val id = if (source == DictionarySource.QQ) {
                uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith(if (category) "cate_id=" else "dict_id=") }
                    ?.substringAfter('=') ?: continue
            } else uri.path.trimEnd('/').substringAfterLast('/')
            if (!id.matches(Regex("[a-zA-Z0-9]{1,40}"))) continue
            // Category and pagination links share the list route; retain their full query.
            output.putIfAbsent(url, CatalogItem(id, title, url, category,
                if (source == DictionarySource.QQ) "qq" else "scel"))
            require(output.size <= 1024)
        }
        require(output.isNotEmpty()) { "Source catalog unavailable" }
        return output.values.toList()
    }

    private fun githubTree(repo: String, prefix: String): List<CatalogItem> {
        val metadata = JSONObject(read("https://api.github.com/repos/$repo"))
        val commit = JSONObject(read("https://api.github.com/repos/$repo/commits/${metadata.getString("default_branch")}"))
            .getString("sha")
        require(commit.matches(Regex("[a-f0-9]{40}")))
        val response = JSONObject(read("https://api.github.com/repos/$repo/git/trees/$commit?recursive=1"))
        require(!response.optBoolean("truncated"))
        val entries = response.getJSONArray("tree")
        return (0 until entries.length()).map { entries.getJSONObject(it) }.filter {
            val path = it.getString("path")
            path.startsWith(prefix) && path.endsWith(".dict.yaml") && it.getString("type") == "blob"
        }.map {
            val path = it.getString("path")
            require(path.matches(Regex("[a-zA-Z0-9_./-]+")) && !path.contains(".."))
            CatalogItem(path.substringAfterLast('/').removeSuffix(".dict.yaml"), path.substringAfterLast('/'),
                "https://raw.githubusercontent.com/$repo/$commit/$path", version = commit)
        }
    }

    private fun zhwiki(): List<CatalogItem> {
        val release = JSONObject(read("https://api.github.com/repos/felixonmars/fcitx5-pinyin-zhwiki/releases/latest"))
        val assets: JSONArray = release.getJSONArray("assets")
        return (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".dict.yaml") }.map {
                CatalogItem(it.getString("name").removeSuffix(".dict.yaml"), it.getString("name"),
                    it.getString("browser_download_url"), version = release.getString("tag_name"))
            }
    }

    private fun read(address: String): String {
        cache.mkdirs()
        val file = File.createTempFile("catalog-", ".tmp", cache)
        return try {
            transport.download(address, file, 4 * 1024 * 1024)
            file.readText(Charsets.UTF_8)
        } finally { file.delete() }
    }

    private fun secureResolve(base: String, href: String): String {
        val resolved = URI(base).resolve(href).toASCIIString()
        return DictionaryDownloadTransport.validate(resolved).toASCIIString()
    }
}
