package dev.zeroinput.languagepack

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

class LanguagePackInstaller(
    private val context: Context,
) {
    private val lock = Any()
    private val installedRoot = File(context.noBackupFilesDir, "language-packs/installed")
    private val stateFile = File(context.noBackupFilesDir, "language-packs/enabled.json")
    /** Last verified filesystem scan; null means discovery has not completed. */
    private var installedCache: List<InstalledLanguagePack>? = null

    fun install(uri: Uri): InstalledLanguagePack {
        val source = context.contentResolver.openInputStream(uri)
            ?: error("Unable to open language pack")
        return source.use(::install)
    }

    fun install(input: InputStream): InstalledLanguagePack {
        val archive = copyArchiveToTemporaryFile(input)
        try {
            return synchronized(lock) {
                validateAndInstall(archive).also(::mergeInstalledCache)
            }
        } finally {
            archive.delete()
        }
    }

    /** Returns verified packages that are present after a process restart. */
    fun listInstalled(): List<InstalledLanguagePack> = synchronized(lock) { scanInstalled() }

    /**
     * Returns the most recent verified scan without touching the filesystem.
     * Before the first background scan this intentionally returns an empty
     * list instead of doing checksum work on a settings/IME UI thread.
     */
    fun installedSnapshot(): List<InstalledLanguagePack> = synchronized(lock) {
        installedCache.orEmpty()
    }

    /** Performs a fresh integrity scan and replaces the in-memory snapshot. */
    fun refreshInstalled(): List<InstalledLanguagePack> = synchronized(lock) {
        scanInstalled()
    }

    private fun scanInstalled(): List<InstalledLanguagePack> {
        val enabled = readEnabledState()
        val result = installedRoot.listFiles { file -> file.isDirectory }.orEmpty()
            .flatMap { idDirectory ->
                idDirectory.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { versionDirectory ->
                    readInstalled(versionDirectory, enabled)
                }
            }
            .sortedWith(compareBy({ it.manifest.displayName }, { it.manifest.id }, { it.manifest.version }))
        return result.also { installedCache = it }
    }

    /**
     * Updates a published snapshot after an install without forcing another
     * full checksum scan.  If discovery has not published a snapshot yet,
     * the pending background refresh remains the source of truth.
     */
    private fun mergeInstalledCache(installed: InstalledLanguagePack) {
        installedCache = installedCache?.let { current ->
            (current.filterNot { it.key == installed.key } + installed)
                .sortedWith(compareBy({ it.manifest.displayName }, { it.manifest.id }, { it.manifest.version }))
        }
    }

    fun installed(): List<InstalledLanguagePack> = listInstalled()

    fun enabled(): List<InstalledLanguagePack> = listInstalled().filter(InstalledLanguagePack::enabled)

    fun setEnabled(pack: InstalledLanguagePack, enabled: Boolean): Boolean =
        setEnabled(pack.manifest.id, pack.manifest.version, enabled)

    fun setEnabled(id: String, version: String, enabled: Boolean): Boolean = runCatching {
        synchronized(lock) {
            val directory = directoryFor(id, version)
            if (!directory.isDirectory || readInstalled(directory, readEnabledState()) == null) return@synchronized false
            val state = readEnabledState()
            state[key(id, version)] = enabled
            writeEnabledState(state)
            installedCache = installedCache?.map { installed ->
                if (installed.key == key(id, version)) installed.copy(enabled = enabled) else installed
            }
            true
        }
    }.getOrDefault(false)

    fun remove(pack: InstalledLanguagePack): Boolean = remove(pack.manifest.id, pack.manifest.version)

    fun removeByKey(packKey: String): Boolean {
        val separator = packKey.lastIndexOf('@')
        if (separator <= 0 || separator == packKey.lastIndex) return false
        return remove(packKey.substring(0, separator), packKey.substring(separator + 1))
    }

    fun remove(id: String, version: String): Boolean = runCatching {
        synchronized(lock) {
            val directory = directoryFor(id, version)
            if (!directory.isDirectory) return@synchronized false
            val removed = directory.deleteRecursively()
            if (removed) {
                val state = readEnabledState()
                state.remove(key(id, version))
                writeEnabledState(state)
                installedCache = installedCache?.filterNot { it.key == key(id, version) }
                directory.parentFile?.let { parent ->
                    if (parent.isDirectory && parent.listFiles().orEmpty().none()) parent.delete()
                }
            }
            removed
        }
    }.getOrDefault(false)

    private fun copyArchiveToTemporaryFile(input: InputStream): File {
        val directory = File(context.cacheDir, "language-pack-import").apply(File::mkdirs)
        val output = File.createTempFile("pack-", ".zip", directory)
        var total = 0L
        try {
            FileOutputStream(output).use { destination ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_ARCHIVE_BYTES) { "Language pack archive is too large" }
                    destination.write(buffer, 0, count)
                }
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun validateAndInstall(archive: File): InstalledLanguagePack = ZipFile(archive).use { zip ->
        require(zip.size() <= MAX_ZIP_ENTRIES) { "Language pack has too many archive entries" }
        val manifestEntry = zip.getEntry(MANIFEST_PATH) ?: error("Language pack manifest is missing")
        require(!manifestEntry.isDirectory && manifestEntry.size in 1..MAX_MANIFEST_BYTES) {
            "Invalid language pack manifest"
        }
        val manifestJson = zip.getInputStream(manifestEntry).use {
            LanguagePackManifestReader.read(it, MAX_MANIFEST_BYTES.toInt())
        }
        val manifest = LanguagePackParser.parse(manifestJson)
        validateArchiveEntries(zip, manifest)

        val staging = createStagingDirectory(manifest)
        try {
            extractVerifiedFiles(zip, manifest, staging)
            require(
                LanguagePackEngineFactory(
                    InstalledLanguagePack(manifest, staging),
                ).isAvailable(),
            ) { "Language pack does not contain a supported dictionary" }
            File(staging, MANIFEST_PATH).writeText(manifestJson, StandardCharsets.UTF_8)
            val destination = destinationFor(manifest)
            replaceDirectory(staging, destination)
            val enabled = readEnabledState()[key(manifest.id, manifest.version)] ?: true
            InstalledLanguagePack(manifest, destination, enabled)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun validateArchiveEntries(zip: ZipFile, manifest: LanguagePackManifest) {
        val declared = manifest.files.map(LanguagePackFile::path).toSet() + MANIFEST_PATH
        val actual = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .map { it.name.replace('\\', '/') }
            .toList()
        require(actual.size == actual.distinct().size) { "Language pack archive has duplicate entries" }
        require(actual.toSet() == declared) { "Language pack archive does not match its manifest" }
        require(manifest.files.sumOf(LanguagePackFile::size) <= MAX_EXTRACTED_BYTES) {
            "Language pack expands beyond the allowed size"
        }
    }

    private fun extractVerifiedFiles(zip: ZipFile, manifest: LanguagePackManifest, staging: File) {
        manifest.files.forEach { declared ->
            val entry = zip.getEntry(declared.path) ?: error("Missing ${declared.path}")
            require(entry.size == declared.size) { "Size mismatch for ${declared.path}" }
            val destination = PackPathPolicy.resolveInside(staging, declared.path)
            destination.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { source ->
                FileOutputStream(destination).use { output ->
                    copyWithDigest(BufferedInputStream(source), output, digest, declared.size)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == declared.sha256) { "Checksum mismatch for ${declared.path}" }
        }
    }

    private fun copyWithDigest(
        source: InputStream,
        output: FileOutputStream,
        digest: MessageDigest,
        expectedSize: Long,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= expectedSize) { "Language pack entry exceeds declared size" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        require(copied == expectedSize) { "Language pack entry is truncated" }
    }

    private fun createStagingDirectory(manifest: LanguagePackManifest): File {
        val root = File(context.noBackupFilesDir, "language-packs/.staging").apply(File::mkdirs)
        return File(root, "${manifest.id}-${System.nanoTime()}").apply {
            require(mkdirs()) { "Unable to create language pack staging directory" }
        }
    }

    private fun destinationFor(manifest: LanguagePackManifest): File {
        return directoryFor(manifest.id, manifest.version)
    }

    private fun replaceDirectory(staging: File, destination: File) {
        destination.parentFile?.mkdirs()
        val backup = File(
            destination.parentFile,
            ".${destination.name}.backup-${System.nanoTime()}",
        )
        var oldMoved = false
        try {
            if (destination.exists()) {
                require(destination.renameTo(backup)) { "Unable to stage the existing language pack" }
                oldMoved = true
            }
            require(staging.renameTo(destination)) { "Unable to activate language pack" }
            if (oldMoved) backup.deleteRecursively()
        } catch (error: Throwable) {
            // Keep the previous verified package available if activation fails.
            if (destination.exists()) destination.deleteRecursively()
            if (oldMoved && backup.exists() && !backup.renameTo(destination)) {
                error.addSuppressed(IllegalStateException("Unable to restore the previous language pack"))
            }
            throw error
        }
    }

    private fun readInstalled(
        directory: File,
        enabled: Map<String, Boolean>,
    ): InstalledLanguagePack? = runCatching {
        val manifestFile = File(directory, MANIFEST_PATH)
        require(manifestFile.isFile) { "Language pack manifest is missing" }
        require(manifestFile.length() in 1..MAX_MANIFEST_BYTES) { "Invalid language pack manifest" }
        val manifest = manifestFile.inputStream().use {
            LanguagePackParser.parse(LanguagePackManifestReader.read(it, MAX_MANIFEST_BYTES.toInt()))
        }
        require(directory.name == manifest.version && directory.parentFile?.name == manifest.id) {
            "Language pack directory does not match its manifest"
        }
        manifest.files.forEach { declared ->
            val payload = PackPathPolicy.resolveInside(directory, declared.path)
            require(payload.isFile && payload.length() == declared.size) {
                "Language pack payload is missing or truncated"
            }
            require(digestMatches(payload, declared)) {
                "Language pack payload checksum mismatch"
            }
        }
        InstalledLanguagePack(manifest, directory, enabled[key(manifest.id, manifest.version)] ?: true)
    }.getOrNull()

    private fun digestMatches(file: File, declared: LanguagePackFile): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        file.inputStream().buffered().use { source ->
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                copied += count
                if (copied > declared.size) return false
                digest.update(buffer, 0, count)
            }
        }
        if (copied != declared.size) return false
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHash == declared.sha256
    }

    private fun directoryFor(id: String, version: String): File {
        require(IDENTIFIER.matches(id)) { "Invalid language pack id" }
        require(IDENTIFIER.matches(version)) { "Invalid language pack version" }
        val root = installedRoot.canonicalFile
        val directory = File(root, "$id/$version").canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) {
            "Language pack path escapes its destination"
        }
        return directory
    }

    private fun readEnabledState(): MutableMap<String, Boolean> {
        if (!stateFile.isFile) return mutableMapOf()
        return runCatching {
            val root = JSONObject(stateFile.readText(StandardCharsets.UTF_8))
            root.keys().asSequence().associateWith { root.optBoolean(it, true) }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeEnabledState(state: Map<String, Boolean>) {
        stateFile.parentFile?.mkdirs()
        val root = JSONObject().apply { state.forEach { (name, value) -> put(name, value) } }
        stateFile.writeText(root.toString(), StandardCharsets.UTF_8)
    }

    private fun key(id: String, version: String): String = "$id@$version"

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_ZIP_ENTRIES = 2_049
        const val MAX_MANIFEST_BYTES = 256L * 1024
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 256L * 1024 * 1024
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
