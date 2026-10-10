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
        "mini-int8" to setOf("mini-int8/model.onnx", "mini-int8/vocab.txt"),
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
        require(license == when (id) {
            "wanxiang-lts" -> "CC-BY-4.0"
            "mini-int8" -> "Apache-2.0"
            else -> "Apache-2.0 AND LGPL-2.1"
        })
        // Reduced kernels require a new runtime/quality review for new graphs.
        if (id == "mini-int8") require(records.associate { it.name to it.sha256 } == mapOf(
            "mini-int8/model.onnx" to "5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446",
            "mini-int8/vocab.txt" to "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
        ))
        if (id == "handwriting") require(records.single { it.name == "handwriting/model.onnx" }.sha256 ==
            "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092")
        return PublicResourcePack(id, version, license, uri.toASCIIString(),
            json.getLong("bytes").also { require(it in 1..550_000_000L) }, hash(json.getString("sha256")), records)
    }

    private fun hash(value: String): String = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
}
