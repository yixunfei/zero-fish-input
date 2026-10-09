package dev.zeroinput.languagepack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import dev.zeroinput.engine.api.PublicDictionaryFile
import dev.zeroinput.engine.api.PublicDictionarySource
import dev.zeroinput.engine.dictionary.importer.PublicDictionaryParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class InstalledPublicDictionary(
    val id: String, val title: String, val source: String, val version: String,
    val sha256: String, val entries: Long, val bytes: Long, val enabled: Boolean,
)

/** Public dictionaries never share a store, key, database or learning port with personal phrases. */
class PublicDictionaryStore(context: Context) : PublicDictionarySource {
    var onPublished: () -> Unit = {}
    private val root = File(context.noBackupFilesDir, "public-dictionaries")
    private val state = AtomicFile(File(root, "installed.json"))
    private val lock = Any()

    fun list(): List<InstalledPublicDictionary> = synchronized(lock) { readState() }

    override fun enabledFiles(): List<PublicDictionaryFile> = synchronized(lock) {
        readState().filter { it.enabled }.map {
            val file = File(root, "${it.sha256}.dict.yaml")
            require(file.isFile && file.length() == it.bytes && sha256(file) == it.sha256)
            PublicDictionaryFile(it.id, file.absolutePath, it.sha256)
        }
    }

    override fun <T> withEnabledFiles(consume: (List<PublicDictionaryFile>) -> T): T =
        synchronized(lock) { consume(enabledFiles()) }

    fun install(id: String, title: String, source: String, version: String,
        input: InputStream, parser: PublicDictionaryParser): InstalledPublicDictionary = synchronized(lock) {
        require(id.matches(Regex("[a-z0-9_]{1,64}")) && title.length in 1..160 && version.length <= 80)
        require(title.none { it.isISOControl() } && version.none { it.isISOControl() })
        require(source in setOf("WANXIANG", "ICE", "ZHWIKI", "QQ", "SOGOU"))
        val existing = readState()
        require(existing.size < 128 || existing.any { it.id == id })
        root.mkdirs()
        // Only this serialized store owns these public scratch files, including after process death.
        root.listFiles().orEmpty().filter {
            (it.name.startsWith("dictionary-") && it.name.endsWith(".tmp")) || it.name.startsWith("index-")
        }.forEach { it.delete() }
        cleanUnusedBlobs()
        val temporary = File.createTempFile("dictionary-", ".tmp", root)
        val databaseFile = File.createTempFile("index-", ".db", root)
        try {
            val count = convert(databaseFile, temporary, id, input, parser)
            require(temporary.length() <= 256L * 1024 * 1024)
            require(existing.filter { it.id != id }.sumOf { it.bytes } + temporary.length() <= 1024L * 1024 * 1024)
            val hash = sha256(temporary)
            val item = InstalledPublicDictionary(id, title, source, version, hash, count, temporary.length(), true)
            checkCancelled()
            val target = File(root, "$hash.dict.yaml")
            if (target.exists()) require(sha256(target) == hash) else check(temporary.renameTo(target))
            checkCancelled()
            writeState(existing.filter { it.id != id } + item)
            cleanUnusedBlobs()
            item
        } finally {
            temporary.delete()
            SQLiteDatabase.deleteDatabase(databaseFile)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val entries = readState()
        require(entries.any { it.id == id })
        writeState(entries.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun disableAll() = synchronized(lock) {
        writeState(readState().map { it.copy(enabled = false) })
    }

    fun remove(id: String) = synchronized(lock) {
        val entries = readState()
        writeState(entries.filter { it.id != id })
        cleanUnusedBlobs()
    }

    private fun cleanUnusedBlobs() {
        val retained = readState().map { "${it.sha256}.dict.yaml" }.toSet()
        root.listFiles().orEmpty().filter { it.name.endsWith(".dict.yaml") && it.name !in retained }
            .forEach { it.delete() }
    }

    private fun convert(databaseFile: File, output: File, id: String, input: InputStream,
        parser: PublicDictionaryParser): Long {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.rawQuery("PRAGMA max_page_count=65536", null).use { require(it.moveToFirst()) }
            database.execSQL("CREATE TABLE words(text TEXT NOT NULL, code TEXT NOT NULL, weight INTEGER NOT NULL, PRIMARY KEY(text,code)) WITHOUT ROWID")
            database.beginTransaction()
            try {
                database.compileStatement("INSERT OR REPLACE INTO words VALUES(?,?,MAX(?,COALESCE((SELECT weight FROM words WHERE text=? AND code=?),0)))").use { statement ->
                    var received = 0L
                    var convertedBytes = 0L
                    parser.parse(input) { entry ->
                        checkCancelled()
                        received++
                        convertedBytes += entry.text.toByteArray(Charsets.UTF_8).size + entry.reading.length + 24
                        require(received <= 5_000_000 && convertedBytes <= 256L * 1024 * 1024)
                        statement.bindString(1, entry.text)
                        statement.bindString(2, entry.reading)
                        statement.bindLong(3, entry.weight)
                        statement.bindString(4, entry.text)
                        statement.bindString(5, entry.reading)
                        statement.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            var count = 0L
            output.bufferedWriter().use { writer ->
                writer.write("---\nname: extra_$id\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n")
                database.rawQuery("SELECT text,code,weight FROM words ORDER BY text,code", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkCancelled()
                        writer.write("${cursor.getString(0)}\t${cursor.getString(1)}\t${cursor.getLong(2)}\n")
                        count++
                    }
                }
            }
            require(count > 0)
            return count
        }
    }

    private fun readState(): List<InstalledPublicDictionary> {
        if (!state.baseFile.exists() && !File(root, "installed.json.bak").exists()) return emptyList()
        val bytes = state.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 256 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= 256 * 1024)
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        require(array.length() <= 128)
        val items = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            InstalledPublicDictionary(entry.getString("id"), entry.getString("title"), entry.getString("source"),
                entry.getString("version"), entry.getString("sha256"), entry.getLong("entries"),
                entry.getLong("bytes"), entry.getBoolean("enabled")).also {
                require(it.id.matches(Regex("[a-z0-9_]{1,64}")) && it.sha256.matches(Regex("[a-f0-9]{64}")))
                require(it.bytes in 1..256L * 1024 * 1024 && it.entries in 1..5_000_000)
            }
        }
        require(items.distinctBy { it.id }.size == items.size)
        return items
    }

    private fun writeState(items: List<InstalledPublicDictionary>) {
        root.mkdirs()
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("id", item.id).put("title", item.title)
            .put("source", item.source).put("version", item.version).put("sha256", item.sha256)
            .put("entries", item.entries).put("bytes", item.bytes).put("enabled", item.enabled)) }
        val output = state.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            checkCancelled()
            state.finishWrite(output)
        } catch (error: Exception) { state.failWrite(output); throw error }
        // Publication is irrevocable at this point, even if the requesting Activity has stopped.
        onPublished()
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
