package dev.zeroinput.languagepack

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class ResourceFileSpec(val name: String, val bytes: Long, val sha256: String)
data class PublicResourcePack(
    val id: String, val version: String, val license: String, val url: String,
    val bytes: Long, val sha256: String, val files: List<ResourceFileSpec>,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("version", version).put("license", license)
        .put("url", url).put("bytes", bytes).put("sha256", sha256).put("files", JSONArray().also { array ->
            files.forEach { array.put(JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256)) }
        })
}

/** Only app-supported data shapes from the project's reviewed release catalog are accepted. */
object PublicResourceCatalog {
    const val ADDRESS = "https://raw.githubusercontent.com/yixunfei/zero-fish-input/main/tools/resources/catalog.json"
    const val MAX_BYTES = 65536
    private val names = mapOf(
        "wanxiang-lts" to setOf("rime/wanxiang-lts-zh-hans.gram"),
        "handwriting" to setOf("handwriting/model.onnx", "handwriting/characters.txt",
            "handwriting/stroke-simplified.zsh", "handwriting/stroke-traditional.zsh"),
    )

    fun parse(text: String): List<PublicResourcePack> {
        require(text.length <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.getInt("format") == 1)
        val array = root.getJSONArray("packs")
        require(array.length() in 1..names.size)
        val packs = (0 until array.length()).map { pack(array.getJSONObject(it)) }
        require(packs.map { it.id }.distinct().size == packs.size)
        return packs
    }

    fun pack(json: JSONObject): PublicResourcePack {
        val id = json.getString("id")
        val expected = requireNotNull(names[id])
        val version = json.getString("version").also { require(it.matches(Regex("[a-f0-9]{16}"))) }
        val uri = URI(json.getString("url"))
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.fragment == null && uri.query == null &&
            uri.path == "/yixunfei/zero-fish-input/releases/download/resources-$version/$id-$version.zip")
        val files = json.getJSONArray("files")
        require(files.length() == expected.size)
        val records = (0 until files.length()).map { index ->
            val file = files.getJSONObject(index)
            val name = file.getString("name")
            require(name in expected)
            val maximum = if (name.endsWith(".gram")) 500_000_000L else if (name.endsWith(".txt")) 256_000L else 80_000_000L
            ResourceFileSpec(name, file.getLong("bytes").also { require(it in 1..maximum) }, hash(file.getString("sha256")))
        }
        require(records.map { it.name }.toSet() == expected)
        val license = json.getString("license")
        require(license == if (id == "wanxiang-lts") "CC-BY-4.0" else "Apache-2.0 AND LGPL-2.1")
        return PublicResourcePack(id, version, license, uri.toASCIIString(),
            json.getLong("bytes").also { require(it in 1..550_000_000L) }, hash(json.getString("sha256")), records)
    }

    private fun hash(value: String): String = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
}
