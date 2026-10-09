package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns only regenerable, non-personal configuration and compiled syllable indexes. */
internal class RimeConfigurationInstaller(
    private val directories: RimeAssetInstaller.Directories,
    private val publicSyllables: List<String>,
    private val deleteUnusedFile: (File) -> Boolean = File::delete,
) {
    fun prepare(options: ChineseInputOptions): Pair<String, File> {
        val id = PinyinAlgebra.schemaId(options)
        val file = File(directories.user, "$id.schema.yaml")
        val expectedVersion = "5.0"
        val current = if (file.isFile) runCatching { JSONObject(file.readText()).getJSONObject("schema").getString("version") }.getOrNull() else null
        if (current != expectedVersion) {
            // JSON is a YAML subset accepted by librime; use a structured parser for edits.
            val schema = JSONObject(File(directories.shared, "zeroinput_pinyin.schema.yaml").readText())
            schema.getJSONObject("schema").put("schema_id", id)
            schema.getJSONObject("schema").put("version", expectedVersion)
            schema.getJSONObject("translator").put("prism", id)
            schema.getJSONObject("speller").put("algebra", JSONArray(PinyinAlgebra.rules(options, publicSyllables)))
            schema.getJSONObject("menu").put("page_size", options.candidatePageSize)
            if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY) {
                schema.getJSONObject("speller").put("alphabet", "abcdefghijklmnopqrstuvwxyz23456789")
                schema.getJSONObject("translator").put("always_show_comments", true)
                schema.getJSONObject("translator").put("spelling_hints", 64)
                schema.getJSONObject("simplifier").put("inherit_comment", true)
            }
            if (options.effectiveDoublePinyinScheme == dev.zeroinput.engine.api.DoublePinyinScheme.MICROSOFT) {
                schema.getJSONObject("speller").put("alphabet", "abcdefghijklmnopqrstuvwxyz;")
            }
            val temporary = Files.createTempFile(directories.user.toPath(), "$id-", ".tmp").toFile()
            temporary.writeText(schema.toString())
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        return id to file
    }

    fun removeUnused(retained: Set<String>) {
        for (directory in listOf(directories.user, File(directories.user, "build"))) {
            directory.listFiles().orEmpty().filter { file ->
                file.name.startsWith("zeroinput_pinyin_") &&
                    retained.none { file.name == "$it.schema.yaml" || file.name == "$it.prism.bin" } &&
                    (file.name.endsWith(".schema.yaml") || file.name.endsWith(".prism.bin") || file.name.endsWith(".tmp"))
            }.forEach { file ->
                // These files are regenerable and are not selected by this session.
                // Retry failed cleanup on the next configuration switch.
                runCatching { deleteUnusedFile(file) }
            }
        }
    }
}
