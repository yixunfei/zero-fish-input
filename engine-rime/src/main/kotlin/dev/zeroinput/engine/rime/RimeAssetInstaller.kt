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

    fun install(): Directories = dictionaries.withEnabledFiles { extras -> installSnapshot(extras) }

    private fun installSnapshot(snapshot: List<dev.zeroinput.engine.api.PublicDictionaryFile>): Directories {
        val root = File(context.noBackupFilesDir, "rime")
        val manifest = context.assets.open("rime/public-dictionaries.json").bufferedReader().use { JSONObject(it.readText()) }
        root.mkdirs()
        root.listFiles().orEmpty().filter {
            it.name.startsWith("prepare-") || it.name.startsWith("base-prepare-")
        }.forEach { it.deleteRecursively() }
        val extras = snapshot.sortedBy { it.id }
        val base = prepareBase(root, manifest)
        val identity = ASSET_VERSION + assetIdentity(manifest) + grammarIdentity() + extras.joinToString { it.id + it.sha256 }
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
                val tables = metadata.getJSONArray("import_tables")
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

    private fun prepareBase(root: File, manifest: JSONObject): File {
        val base = File(root, "base-$ASSET_VERSION-${assetIdentity(manifest)}-${if (managedResources) "managed" else "bundled"}")
        if (File(base, ".installed").isFile) return base
        val staging = java.nio.file.Files.createTempDirectory(root.toPath(), "base-prepare-").toFile()
        try {
            copyAssetTree(ASSET_ROOT, staging)
            val files = manifest.getJSONObject("files")
            files.keys().forEach { name ->
                if (managedResources && name.endsWith(".gram")) return@forEach
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
        val root = File(context.noBackupFilesDir, "rime")
        root.listFiles().orEmpty().filter {
            (it.name.startsWith("shared-") || it.name.startsWith("user-")) && it.name !in keep
        }.forEach { it.deleteRecursively() }
        val referencedBases = retained.map {
            File(it.shared, "dicts/zi.dict.yaml").canonicalFile.parentFile?.parentFile
        }.toSet()
        root.listFiles().orEmpty().filter {
            it.name.startsWith("base-") && it.canonicalFile !in referencedBases
        }.forEach { it.deleteRecursively() }
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        if (managedResources && assetPath.endsWith(".gram")) return
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        }

        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private companion object {
        const val ASSET_ROOT = "rime"
        const val ASSET_VERSION = "8"
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
