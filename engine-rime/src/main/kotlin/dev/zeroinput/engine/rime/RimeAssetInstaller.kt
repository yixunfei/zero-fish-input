package dev.zeroinput.engine.rime

import android.content.Context
import java.io.File
import dev.zeroinput.engine.api.PublicDictionarySource
import org.json.JSONObject
import java.security.MessageDigest

internal class RimeAssetInstaller(
    private val context: Context,
    private val dictionaries: PublicDictionarySource = PublicDictionarySource.Empty,
    private val managedResources: Boolean = false,
    private val grammar: dev.zeroinput.engine.api.PublicResourceFile? = null,
) {
    private val archives by lazy {
        context.assets.open("rime/dictionary-archives.json").bufferedReader().use { reader ->
            JSONObject(reader.readText()).also { require(it.getInt("format") == 1) }.getJSONObject("files")
        }
    }
    data class Directories(
        val shared: File,
        val user: File,
    )

    fun lastVerified(): Directories? {
        val root = File(context.noBackupFilesDir, "rime")
        val marker = android.util.AtomicFile(File(root, "verified-generation"))
        return runCatching {
            val version = marker.openRead().use { input ->
                val bytes = ByteArray(25)
                val count = input.read(bytes)
                require(count == 24 && input.read() == -1)
                String(bytes, 0, count, Charsets.US_ASCII)
            }
            require(version.matches(Regex("[a-f0-9]{24}")))
            Directories(File(root, "shared-$version"), File(root, "user-$version"))
                .takeIf { File(it.shared, ".installed").isFile && it.user.isDirectory }
        }.getOrNull()
    }

    fun markVerified(directories: Directories) {
        val marker = android.util.AtomicFile(File(directories.shared.parentFile, "verified-generation"))
        val stream = marker.startWrite()
        try {
            stream.write(directories.shared.name.removePrefix("shared-").toByteArray(Charsets.US_ASCII))
            marker.finishWrite(stream)
        } catch (error: Exception) { marker.failWrite(stream); throw error }
    }

    fun install(): Directories = dictionaries.withSnapshot { installSnapshot(it) }

    private fun installSnapshot(snapshot: dev.zeroinput.engine.api.PublicDictionarySnapshot): Directories {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Dictionary deployment requires worker thread" }
        val root = File(context.noBackupFilesDir, "rime")
        val manifest = context.assets.open("rime/public-dictionaries.json").bufferedReader().use { JSONObject(it.readText()) }
        root.mkdirs()
        root.listFiles().orEmpty().filter {
            it.name.startsWith("prepare-") || it.name.startsWith("base-prepare-")
        }.forEach { it.deleteRecursively() }
        val extras = snapshot.extraFiles.sortedBy { it.id }
        val skipped = snapshot.disabledBuiltInTables + snapshot.builtInReplacements.keys
        require("zi" !in snapshot.disabledBuiltInTables)
        val knownTables = manifest.getJSONObject("files").keys().asSequence()
            .filter { it.startsWith("dicts/") }.map { it.removePrefix("dicts/").removeSuffix(".dict.yaml") }.toSet()
        require(skipped.all { it in knownTables })
        val base = prepareBase(root, manifest, skipped)
        val identity = ASSET_VERSION + assetIdentity(manifest) + grammarIdentity() + extras.joinToString { it.id + it.sha256 } +
            snapshot.disabledBuiltInTables.sorted().joinToString() +
            snapshot.builtInReplacements.toSortedMap().entries.joinToString { it.key + it.value.sha256 }
        val version = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val shared = File(root, "shared-$version")
        val user = File(root, "user-$version").apply(File::mkdirs)
        val marker = File(shared, ".installed")
        if (!marker.isFile) {
            val staging = java.nio.file.Files.createTempDirectory(root.toPath(), "prepare-").toFile()
            try {
                linkTree(base, staging)
                if (managedResources) {
                    val schema = File(staging, "zeroinput_pinyin.schema.yaml")
                    val config = JSONObject(schema.readText())
                    if (grammar == null) config.remove("grammar")
                    schema.writeText(config.toString())
                    if (grammar != null) android.system.Os.symlink(grammar.path,
                        File(staging, "wanxiang-lts-zh-hans.gram").absolutePath)
                }
                val dictionary = File(staging, "zeroinput_public.dict.yaml")
                val metadata = JSONObject(dictionary.readLines().drop(1).takeWhile { it.trim() != "..." }.joinToString("\n"))
                val originalTables = metadata.getJSONArray("import_tables")
                val tables = org.json.JSONArray()
                (0 until originalTables.length()).map { originalTables.getString(it) }
                    .filterNot { it.removePrefix("dicts/") in snapshot.disabledBuiltInTables }.forEach(tables::put)
                metadata.put("import_tables", tables)
                snapshot.builtInReplacements.forEach { (table, entry) -> publishReplacement(staging, table, entry) }
                extras.forEach { entry ->
                    require(entry.id.matches(Regex("[a-z0-9_]{1,64}")))
                    val target = File(staging, "extra_${entry.id}.dict.yaml")
                    File(entry.path).copyTo(target)
                    require(sha256(target) == entry.sha256)
                    tables.put("extra_${entry.id}")
                }
                metadata.put("version", version)
                // Replace the reference before writing generation-specific metadata.
                check(dictionary.delete())
                dictionary.writeText("---\n$metadata\n...\n")
                File(staging, ".installed").writeText(version)
                check(staging.renameTo(shared)) { "Unable to activate public dictionary generation" }
            } finally { staging.deleteRecursively() }
        }
        return Directories(shared, user)
    }

    private fun publishReplacement(staging: File, table: String, entry: dev.zeroinput.engine.api.PublicDictionaryFile) {
        val target = File(staging, "dicts/$table.dict.yaml")
        target.parentFile?.mkdirs()
        require(sha256(File(entry.path)) == entry.sha256)
        target.bufferedWriter().use { output ->
            output.write("---\nname: $table\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n")
            File(entry.path).bufferedReader().use { reader ->
                var data = false
                var headerLines = 0
                reader.forEachLine { line ->
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    if (data) output.appendLine(line) else {
                        require(++headerLines <= 16)
                        if (line == "...") data = true
                    }
                }
                require(data)
            }
        }
    }

    private fun prepareBase(root: File, manifest: JSONObject, skipped: Set<String>): File {
        val selectionHash = MessageDigest.getInstance("SHA-256").digest(skipped.sorted().joinToString().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val base = File(root, "base-$ASSET_VERSION-${assetIdentity(manifest)}-${if (managedResources) "managed" else "bundled"}-$selectionHash")
        if (File(base, ".installed").isFile) return base
        val staging = java.nio.file.Files.createTempDirectory(root.toPath(), "base-prepare-").toFile()
        try {
            copyAssetTree(ASSET_ROOT, staging, skipped)
            val files = manifest.getJSONObject("files")
            files.keys().forEach { name ->
                if (managedResources && name.endsWith(".gram")) return@forEach
                if (name.startsWith("dicts/") && name.removePrefix("dicts/").removeSuffix(".dict.yaml") in skipped) return@forEach
                require(!name.contains("..") && !name.startsWith('/'))
                require(sha256(File(staging, name)) == files.getString(name)) { "Damaged public dictionary asset" }
            }
            File(staging, ".installed").writeText(manifest.getString("revision"))
            check(staging.renameTo(base))
        } finally { staging.deleteRecursively() }
        return base
    }

    private fun assetIdentity(manifest: JSONObject): String {
        val files = manifest.getJSONObject("files")
        val identity = files.keys().asSequence().sorted().joinToString { it + files.getString(it) }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun linkTree(source: File, destination: File) {
        destination.mkdirs()
        source.listFiles().orEmpty().forEach { file ->
            val target = File(destination, file.name)
            if (file.isDirectory) linkTree(file, target)
            else if (file.name != ".installed") {
                if (file.name.endsWith(".gram") || file.name.endsWith(".dict.yaml")) {
                    // Only app-generated references inside its private root; imports cannot create links.
                    android.system.Os.symlink(file.absolutePath, target.absolutePath)
                } else file.copyTo(target)
            }
        }
    }

    /** Called only after native verification, when no editor owns an old generation. */
    fun removeUnused(retained: Set<Directories>) {
        val keep = retained.flatMap { listOf(it.shared.name, it.user.name) }.toSet()
        // Android may expose noBackupFilesDir through /data/user/0 while
        // canonical symlink targets use /data/data. Compare canonical roots.
        val root = File(context.noBackupFilesDir, "rime").canonicalFile
        root.listFiles().orEmpty().filter {
            (it.name.startsWith("shared-") || it.name.startsWith("user-")) && it.name !in keep
        }.forEach { it.deleteRecursively() }
        val referencedBases = retained.flatMap { directory ->
            directory.shared.walkTopDown().filter { it.isFile }.map { it.canonicalFile }
                .mapNotNull { file -> generateSequence(file.parentFile) { it.parentFile }
                    .firstOrNull { it.parentFile == root && it.name.startsWith("base-") } }.toList()
        }.toSet()
        root.listFiles().orEmpty().filter {
            it.name.startsWith("base-") && it.canonicalFile !in referencedBases
        }.forEach { it.deleteRecursively() }
    }

    private fun copyAssetTree(assetPath: String, destination: File, skipped: Set<String>) {
        if (managedResources && assetPath.endsWith(".gram")) return
        if (assetPath.startsWith("rime/dicts/") &&
            assetPath.removePrefix("rime/dicts/").removeSuffix(".xz").removeSuffix(".dict.yaml") in skipped) return
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            if (assetPath.endsWith(".dict.yaml.xz")) {
                val spec = archives.getJSONObject(assetPath.removePrefix("rime/").removeSuffix(".xz"))
                val output = File(destination.parentFile, destination.name.removeSuffix(".xz"))
                output.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    output.outputStream().use { stream ->
                        XzDictionaryReader.copy(input, stream, XzDictionaryReader.Spec(spec.getLong("bytes"),
                            spec.getString("sha256"), spec.getLong("archiveBytes"), spec.getString("archiveSha256")))
                    }
                }
                return
            }
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        }

        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child), skipped)
        }
    }

    private companion object {
        const val ASSET_ROOT = "rime"
        const val ASSET_VERSION = "10"
    }

    private fun grammarIdentity(): String = if (managedResources) grammar?.sha256 ?: "none" else "bundled"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
