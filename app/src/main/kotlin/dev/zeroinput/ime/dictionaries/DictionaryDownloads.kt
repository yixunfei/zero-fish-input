package dev.zeroinput.ime.dictionaries

import android.content.Context
import android.util.AtomicFile
import dev.zeroinput.engine.dictionary.importer.CellDictionaryParser
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.languagepack.BuiltInDictionaryStore
import dev.zeroinput.languagepack.InstalledPublicDictionary
import dev.zeroinput.languagepack.PublicDictionaryStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Foreground downloads only. Receipts contain public source metadata, never local names or entries. */
internal class DictionaryDownloads(
    private val context: Context,
    private val transport: DictionaryDownloadTransport,
    private val catalog: DictionaryCatalog,
    private val store: PublicDictionaryStore,
    private val bundled: BuiltInDictionaryStore,
) {
    fun install(source: DictionarySource, item: CatalogItem, progress: (Long, Long) -> Unit) {
        val file = File.createTempFile("public-download-", ".tmp", context.cacheDir)
        try {
            transport.download(catalog.resolve(source, item), file, 128L * 1024 * 1024, progress = progress)
            val parser = when (item.format) {
                "qq" -> CellDictionaryParser(CellDictionaryParser.Source.QQ)
                "scel" -> CellDictionaryParser(CellDictionaryParser.Source.SOGOU)
                else -> RimeTextDictionaryParser()
            }
            val installed = file.inputStream().use { store.install(identity(source, item), item.title, source.name, item.version, it, parser) }
            saveReceipt(source, item, installed.sha256)
        } finally { file.delete() }
    }

    fun redownload(item: InstalledPublicDictionary, progress: (Long, Long) -> Unit) {
        val source = DictionarySource.valueOf(item.source)
        val receipt = receipt(item.id)?.takeIf { it.optString("sha256") == item.sha256 }
        val selected = if (receipt != null) {
            require(receipt.getString("source") == source.name)
            CatalogItem(receipt.getString("id"), item.title, receipt.getString("address"),
                format = receipt.getString("format"), version = item.version)
        } else catalog.list(source).single { !it.category && identity(source, it) == item.id }
        require(identity(source, selected) == item.id)
        install(source, selected, progress)
    }

    fun redownloadBuiltIn(table: String, progress: (Long, Long) -> Unit) {
        require(table in bundled.tables())
        val archive = File.createTempFile("public-builtin-", ".zip", context.cacheDir)
        try {
            transport.download(bundled.archiveAddress(), archive, 512L * 1024 * 1024, progress = progress)
            withVerifiedEntry(archive, bundled.archiveSha256(), bundled.revision(), table) {
                store.install("builtin_$table", table, DictionarySource.WANXIANG.name, bundled.revision(), it, RimeTextDictionaryParser())
            }
        } finally { archive.delete() }
    }

    private fun receiptFile(id: String): AtomicFile {
        require(id.matches(Regex("[a-z0-9_]{1,64}")))
        return AtomicFile(File(context.noBackupFilesDir, "dictionary-sources/$id.json"))
    }

    private fun receipt(id: String): JSONObject? {
        val file = receiptFile(id)
        if (!file.baseFile.exists()) return null
        require(file.baseFile.length() <= 8192)
        return file.openRead().bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun saveReceipt(source: DictionarySource, item: CatalogItem, sha256: String) {
        require(item.address.length <= 2048 && item.id.length <= 160)
        DictionaryDownloadTransport.validate(item.address)
        val file = receiptFile(identity(source, item))
        file.baseFile.parentFile?.mkdirs()
        val retained = store.list().map { "${it.id}.json" }.toSet()
        file.baseFile.parentFile?.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name !in retained }
            .forEach { it.delete() }
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("source", source.name).put("id", item.id).put("address", item.address)
                .put("format", item.format).put("sha256", sha256).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        fun identity(source: DictionarySource, item: CatalogItem): String = source.name.lowercase() + "_" +
            MessageDigest.getInstance("SHA-256").digest(item.id.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }

        internal fun <T> withVerifiedEntry(archive: File, hash: String, revision: String, table: String,
            consume: (java.io.InputStream) -> T): T {
            require(archive.length() in 1..(512L * 1024 * 1024))
            require(revision.matches(Regex("[a-f0-9]{40}")) && table.matches(Regex("[a-z]{1,16}")))
            require(sha256(archive) == hash) { "Unverified dictionary archive" }
            return ZipFile(archive).use { zip ->
                require(zip.size() in 1..10000)
                val entries = zip.entries().asSequence().toList()
                require(entries.map { it.name }.distinct().size == entries.size)
                val name = "rime-wanxiang-$revision/dicts/$table.dict.yaml"
                val entry = entries.single { it.name == name }
                require(!entry.isDirectory && entry.size in 1..(256L * 1024 * 1024))
                // No archive path is extracted and no other file is interpreted.
                zip.getInputStream(entry).use { input ->
                    val bounded = object : java.io.FilterInputStream(input) {
                        var remaining = entry.size
                        override fun read(): Int = super.read().also { if (it >= 0) { require(remaining > 0); remaining-- } }
                        override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
                            `in`.read(buffer, offset, size).also { if (it > 0) { require(it <= remaining); remaining -= it } }
                    }
                    consume(bounded)
                }
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
