package dev.zeroinput.ime.dictionaries

import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal enum class DictionarySource(val website: String, val format: String = "rime") {
    WANXIANG("https://github.com/amzxyz/rime-wanxiang"),
    ICE("https://github.com/iDvel/rime-ice"),
    ZHWIKI("https://github.com/felixonmars/fcitx5-pinyin-zhwiki/releases/latest"),
    QQ("https://cdict.qq.pinyin.cn/", "qq"),
    SOGOU("https://pinyin.sogou.com/dict/", "scel");

    val downloadLimit: Long get() = if (format == "rime") 128L * 1024 * 1024 else 32L * 1024 * 1024
}
internal data class CatalogItem(
    val id: String, val title: String, val address: String,
    val category: Boolean = false, val format: String = "rime", val version: String = "",
)

/** Converts bounded public catalogs into native rows. HTML scripts are never executed. */
internal class DictionaryCatalog(private val cache: File, private val transport: DictionaryFileDownload) {
    fun list(source: DictionarySource, page: String? = null): List<CatalogItem> = when (source) {
        DictionarySource.QQ, DictionarySource.SOGOU -> website(source, page ?: source.website)
        DictionarySource.ICE -> githubTree("iDvel/rime-ice", "cn_dicts/")
        DictionarySource.WANXIANG -> githubTree("amzxyz/rime-wanxiang", "dicts/")
        DictionarySource.ZHWIKI -> zhwiki()
    }

    fun resolve(source: DictionarySource, item: CatalogItem): String {
        require(!item.category && item.format == source.format)
        return when (source) {
            DictionarySource.QQ -> {
                val detail = DictionaryDownloadTransport.validate(item.address)
                require(detail.host == "cdict.qq.pinyin.cn" && detail.path == "/detail" &&
                    item.id.matches(ID) && queryValue(detail, "dict_id") == item.id)
                "${source.website}download?dict_id=${item.id}"
            }
            DictionarySource.SOGOU -> resolveSogou(item) { read(item.address) }
            else -> validatedAddress(item.address)
        }
    }

    internal fun resolveSogou(item: CatalogItem, html: () -> String): String {
        val detail = DictionaryDownloadTransport.validate(item.address)
        require(detail.host == "pinyin.sogou.com" && item.id.matches(ID) &&
            detail.path.trimEnd('/') == "/dict/detail/index/${item.id}")
        return Jsoup.parse(html(), item.address).select("a[href]").firstNotNullOfOrNull { link ->
            val address = runCatching { secureResolve(item.address, link.attr("href").replace(" ", "%20")) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            val uri = URI(address)
            address.takeIf {
                uri.host == detail.host && uri.path == "/d/dict/download_cell.php" &&
                    queryValue(uri, "id") == item.id && !queryValue(uri, "name").isNullOrBlank()
            }
        } ?: error("Source has no supported download")
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
                queryValue(uri, if (category) "cate_id" else "dict_id") ?: continue
            } else uri.path.trimEnd('/').substringAfterLast('/')
            if (!id.matches(ID)) continue
            // Category and pagination links share the list route; retain their full query.
            output.putIfAbsent(url, CatalogItem(id, title, url, category, source.format))
            require(output.size <= MAX_ITEMS)
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
        return parseGithubTree(response, repo, prefix, commit)
    }

    internal fun parseGithubTree(response: JSONObject, repo: String, prefix: String, commit: String): List<CatalogItem> {
        require(commit.matches(Regex("[a-f0-9]{40}")))
        require(!response.optBoolean("truncated"))
        val entries = response.getJSONArray("tree")
        val output = ArrayList<CatalogItem>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val path = entry.getString("path")
            if (!path.startsWith(prefix) || !path.endsWith(".dict.yaml") || entry.getString("type") != "blob") continue
            if (entry.optLong("size", -1) > DictionarySource.WANXIANG.downloadLimit) continue
            require(output.size < MAX_ITEMS)
            require(path.matches(Regex("[a-zA-Z0-9_./-]+")) && !path.contains(".."))
            output += CatalogItem(path.substringAfterLast('/').removeSuffix(".dict.yaml"), path.substringAfterLast('/'),
                validatedAddress("https://raw.githubusercontent.com/$repo/$commit/$path"), version = commit)
        }
        return output
    }

    private fun zhwiki(): List<CatalogItem> {
        return parseZhwiki(read("https://api.github.com/repos/felixonmars/fcitx5-pinyin-zhwiki/releases/latest"))
    }

    internal fun parseZhwiki(json: String): List<CatalogItem> {
        val release = JSONObject(json)
        val assets: JSONArray = release.getJSONArray("assets")
        val output = ArrayList<CatalogItem>()
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            if (!name.endsWith(".dict.yaml")) continue
            if (asset.optLong("size", -1) > DictionarySource.ZHWIKI.downloadLimit) continue
            require(output.size < MAX_ITEMS)
            output += CatalogItem(name.removeSuffix(".dict.yaml"), name,
                validatedAddress(asset.getString("browser_download_url")), version = release.getString("tag_name"))
        }
        return output
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
        return validatedAddress(resolved)
    }

    private fun validatedAddress(address: String): String = DictionaryDownloadTransport.validate(address).toASCIIString()

    private fun queryValue(uri: URI, key: String): String? = uri.rawQuery.orEmpty().split('&')
        .filter { it.startsWith("$key=") }.singleOrNull()?.substringAfter('=')

    private companion object {
        const val MAX_ITEMS = 1024
        val ID = Regex("[a-zA-Z0-9]{1,40}")
    }
}
