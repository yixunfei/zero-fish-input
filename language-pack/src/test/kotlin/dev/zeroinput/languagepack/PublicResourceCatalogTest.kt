package dev.zeroinput.languagepack

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicResourceCatalogTest {
    @Test fun miniDownloadRejectsUnreviewedGraphAndMissingVocabulary() {
        val value = pack().put("id", "mini-int8").put("license", "Apache-2.0")
            .put("url", "https://github.com/yixunfei/zero-fish-input/releases/download/resources-0123456789abcdef/mini-int8-0123456789abcdef.zip")
            .put("files", JSONArray()
                .put(JSONObject().put("name", "mini-int8/model.onnx").put("bytes", 14898764)
                    .put("sha256", "5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446"))
                .put(JSONObject().put("name", "mini-int8/vocab.txt").put("bytes", 109540)
                    .put("sha256", "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c")))
        assertEquals("mini-int8", PublicResourceCatalog.pack(value).id)
        value.getJSONArray("files").getJSONObject(0).put("sha256", "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.pack(value) }
        value.getJSONArray("files").remove(1)
        assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.pack(value) }
    }
    private fun pack() = JSONObject().put("id", "wanxiang-lts").put("version", "0123456789abcdef")
        .put("license", "CC-BY-4.0").put("bytes", 20).put("sha256", "a".repeat(64))
        .put("url", "https://github.com/yixunfei/zero-fish-input/releases/download/resources-0123456789abcdef/wanxiang-lts-0123456789abcdef.zip")
        .put("files", JSONArray().put(JSONObject().put("name", "rime/wanxiang-lts-zh-hans.gram")
            .put("bytes", 10).put("sha256", "b".repeat(64))))

    @Test fun acceptsOnlyKnownCompatibleResourceShapes() {
        assertEquals("wanxiang-lts", PublicResourceCatalog.pack(pack()).id)
        listOf("../model.gram", "/model.gram", "rime/native.so", "rime/model.onnx").forEach { name ->
            val value = pack()
            value.getJSONArray("files").getJSONObject(0).put("name", name)
            assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.pack(value) }
        }
    }

    @Test fun rejectsUntrustedReleaseLocationsAndOversizedModels() {
        listOf("https://example.com/model.zip", "http://github.com/file", "https://github.com/other/repo/releases/file").forEach {
            assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.pack(pack().put("url", it)) }
        }
        val value = pack()
        value.getJSONArray("files").getJSONObject(0).put("bytes", 500_000_001L)
        assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.pack(value) }
    }

    @Test fun rejectsDuplicatePackIdentitiesAndUnsupportedLicenses() {
        val root = JSONObject().put("format", 1).put("packs", JSONArray().put(pack()).put(pack()))
        assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.parse(root.toString()) }
        assertThrows(IllegalArgumentException::class.java) { PublicResourceCatalog.pack(pack().put("license", "unknown")) }
    }
}
