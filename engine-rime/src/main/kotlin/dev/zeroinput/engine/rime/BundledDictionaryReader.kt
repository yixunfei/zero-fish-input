package dev.zeroinput.engine.rime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** Settings worker access to verified public assets; temporary files contain no user edits. */
class BundledDictionaryReader(private val context: Context) {
    fun <T> withInput(table: String, consume: (InputStream) -> T): T {
        require(table.matches(Regex("[a-z]{1,16}")))
        val manifest = context.assets.open("rime/dictionary-archives.json").bufferedReader().use { JSONObject(it.readText()) }
        val name = "dicts/$table.dict.yaml"
        val spec = manifest.getJSONObject("files").getJSONObject(name)
        val temporary = File.createTempFile("public-builtin-", ".yaml", context.cacheDir)
        try {
            context.assets.open("rime/$name.xz").use { input ->
                temporary.outputStream().use { output ->
                    XzDictionaryReader.copy(input, output, XzDictionaryReader.Spec(spec.getLong("bytes"),
                        spec.getString("sha256"), spec.getLong("archiveBytes"), spec.getString("archiveSha256")))
                }
            }
            return temporary.inputStream().use(consume)
        } finally { temporary.delete() }
    }
}
