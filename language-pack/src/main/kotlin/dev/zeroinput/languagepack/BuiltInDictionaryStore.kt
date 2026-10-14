package dev.zeroinput.languagepack

import android.content.Context
import android.util.AtomicFile
import dev.zeroinput.engine.api.PublicDictionaryFile
import dev.zeroinput.engine.api.PublicDictionarySnapshot
import dev.zeroinput.engine.api.PublicDictionarySource
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

data class BuiltInDictionary(
    val table: String, val version: String, val enabled: Boolean, val deleted: Boolean,
    val bytes: Long, val replacement: InstalledPublicDictionary?,
) {
    val id: String get() = "builtin_$table"
    val required: Boolean get() = table == "zi"
}

/** Only public selection is stored here. User-authored names and entries belong to user-data. */
class BuiltInDictionaryStore(context: Context, private val downloads: PublicDictionaryStore) : PublicDictionarySource {
    private val assets = context.assets
    private val root = File(context.noBackupFilesDir, "builtin-dictionaries")
    private val state = AtomicFile(File(root, "selection.json"))
    private val lock = Any()
    private val catalog by lazy {
        assets.open("public-resources/bundled-dictionaries.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    private val archives by lazy {
        assets.open("rime/dictionary-archives.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("files") }
    }
    var onPublished: () -> Unit = {}

    fun tables(): List<String> = catalog.getJSONArray("tables").let { array ->
        (0 until array.length()).map { array.getString(it) }.also {
            require(it.size == 16 && "zi" in it && it.distinct().size == it.size)
            require(it.all { name -> name.matches(Regex("[a-z]{1,16}")) })
        }
    }

    fun list(): List<BuiltInDictionary> = synchronized(lock) {
        val selection = readState()
        val installed = downloads.list().associateBy { it.id }
        tables().map { table ->
            val value = selection[table] ?: "enabled"
            BuiltInDictionary(table, catalog.getString("version"), value == "enabled", value == "deleted",
                archives.getJSONObject("dicts/$table.dict.yaml").getLong("bytes"), installed["builtin_$table"])
        }
    }

    fun setEnabled(table: String, enabled: Boolean) = mutate(table, if (enabled) "enabled" else "disabled")

    fun remove(table: String) = synchronized(lock) {
        require(table != "zi")
        mutate(table, "deleted")
        downloads.remove("builtin_$table")
    }

    fun restore(table: String) = synchronized(lock) {
        require(table in tables())
        downloads.remove("builtin_$table")
        mutate(table, "enabled")
    }

    fun restoreAll() = synchronized(lock) {
        tables().forEach { downloads.remove("builtin_$it") }
        writeState(emptyMap())
    }

    fun archiveAddress(): String = catalog.getJSONObject("archive").getString("url")
    fun archiveSha256(): String = catalog.getJSONObject("archive").getString("sha256")
    fun revision(): String = catalog.getString("revision")

    fun activeIds(): Set<String> = synchronized(lock) {
        list().filter { it.enabled }.mapTo(HashSet()) { it.id } +
            downloads.list().filter { it.enabled && !it.id.startsWith("builtin_") }.map { it.id }
    }

    override fun enabledFiles(): List<PublicDictionaryFile> = withSnapshot { it.extraFiles }

    override fun <T> withEnabledFiles(consume: (List<PublicDictionaryFile>) -> T): T = withSnapshot { consume(it.extraFiles) }

    override fun <T> withSnapshot(consume: (PublicDictionarySnapshot) -> T): T = synchronized(lock) {
        val disabled = readState().filterValues { it != "enabled" }.keys
        downloads.withEnabledFiles { files ->
            val replacements = files.filter { it.id.startsWith("builtin_") }
                .associateBy { it.id.removePrefix("builtin_") }
            require(replacements.keys.all { it in tables() })
            consume(PublicDictionarySnapshot(files.filterNot { it.id.startsWith("builtin_") }, disabled,
                replacements.filterKeys { it !in disabled }))
        }
    }

    private fun mutate(table: String, value: String) = synchronized(lock) {
        require(table in tables() && (table != "zi" || value == "enabled"))
        val current = readState().toMutableMap()
        if (value == "enabled") current.remove(table) else current[table] = value
        writeState(current)
    }

    private fun readState(): Map<String, String> {
        if (!state.baseFile.exists() && !File(root, "selection.json.bak").exists()) return emptyMap()
        val bytes = state.openRead().use { input -> val buffer = ByteArray(4097); var size = 0
            while (size < buffer.size) { val count = input.read(buffer, size, buffer.size - size); if (count < 0) break; size += count }
            buffer.copyOf(size) }
        require(bytes.size <= 4096)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.length() <= 16)
        return json.keys().asSequence().associateWith { key ->
            require(key in tables() && key != "zi")
            json.getString(key).also { require(it in setOf("disabled", "deleted")) }
        }
    }

    private fun writeState(values: Map<String, String>) {
        root.mkdirs()
        val output = state.startWrite()
        try {
            output.write(JSONObject(values).toString().toByteArray(Charsets.UTF_8))
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            state.finishWrite(output)
        } catch (error: Exception) { state.failWrite(output); throw error }
        onPublished()
    }
}
