package dev.zeroinput.languagepack

import android.content.Context
import android.util.AtomicFile
import dev.zeroinput.engine.api.PublicResourceFile
import dev.zeroinput.engine.api.PublicResourceLease
import dev.zeroinput.engine.api.PublicResourceSource
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

data class PublicResourceStatus(val pack: PublicResourcePack, val enabled: Boolean,
    val downloaded: Boolean, val bundled: Boolean)

/** Worker-only disk operations. Immutable content is shared by bundled and downloaded resources. */
class PublicResourceStore(context: Context) : PublicResourceSource {
    private val context = context.applicationContext
    private val root = File(context.noBackupFilesDir, "public-resources")
    private val monitor = Any()
    private val leases = mutableMapOf<String, Int>()
    private val counter = AtomicLong()
    override val revision: Long get() = counter.get()
    var onPublished: () -> Unit = {}

    fun bundledCatalog(): List<PublicResourcePack> = context.assets.open("public-resources/catalog.json")
        .use { PublicResourceCatalog.parse(String(readBounded(it), Charsets.UTF_8)) }

    fun list(): List<PublicResourceStatus> = synchronized(monitor) {
        val state = readState()
        bundledCatalog().map { base ->
            val row = state.optJSONObject(base.id)
            val installed = row?.optJSONObject("pack")?.let(PublicResourceCatalog::pack)
            PublicResourceStatus(installed ?: base, row?.optBoolean("enabled", true) ?: true,
                installed != null, isBundled(base))
        }
    }

    override fun acquire(id: String): PublicResourceLease? = synchronized(monitor) {
        val state = readState().optJSONObject(id)
        if (state?.optBoolean("enabled", true) == false) return null
        val downloaded = state?.optJSONObject("pack")?.let(PublicResourceCatalog::pack)
        val pack = downloaded ?: bundledCatalog().singleOrNull { it.id == id } ?: return null
        if (downloaded == null && !isBundled(pack)) return null
        val files = pack.files.associate { spec ->
            val blob = blob(spec.sha256)
            if (!valid(blob, spec)) {
                check(downloaded == null) { "Resource unavailable" }
                val temporary = stagingFile()
                try {
                    context.assets.open(spec.name).use { copyVerified(it, temporary, spec) }
                    publishBlob(temporary, blob)
                } finally { temporary.delete() }
            }
            spec.name to PublicResourceFile(blob.absolutePath, spec.bytes, spec.sha256)
        }
        files.values.forEach { leases[it.sha256] = (leases[it.sha256] ?: 0) + 1 }
        object : PublicResourceLease {
            override val files = files
            private var closed = false
            override fun close() = synchronized(monitor) {
                if (!closed) {
                    closed = true
                    files.values.forEach { file ->
                        val remaining = (leases[file.sha256] ?: 1) - 1
                        if (remaining == 0) leases.remove(file.sha256) else leases[file.sha256] = remaining
                    }
                }
            }
        }
    }

    fun install(pack: PublicResourcePack, archive: File) = synchronized(monitor) {
        PublicResourceCatalog.pack(pack.json())
        require(valid(archive, ResourceFileSpec("archive", pack.bytes, pack.sha256)))
        val staging = java.nio.file.Files.createTempDirectory(directory().toPath(), "install-").toFile()
        try {
            try { unpack(pack, archive, staging) }
            catch (_: java.util.zip.ZipException) { throw IllegalArgumentException("Invalid resource archive") }
            active()
            pack.files.forEach { publishBlob(File(staging, it.sha256), blob(it.sha256)) }
            val state = readState()
            state.put(pack.id, JSONObject().put("enabled", true).put("pack", pack.json()))
            active()
            writeState(state)
            published()
            collectUnused(state)
        } finally { staging.deleteRecursively() }
    }

    private fun unpack(pack: PublicResourcePack, archive: File, staging: File) {
        val remaining = pack.files.associateBy { it.name }.toMutableMap()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                active()
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory)
                val spec = requireNotNull(remaining.remove(entry.name)) { "Unexpected resource entry" }
                require(entry.size == -1L || entry.size == spec.bytes)
                copyVerified(zip, File(staging, spec.sha256), spec)
                zip.closeEntry()
            }
        }
        require(remaining.isEmpty()) { "Incomplete resource" }
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(monitor) {
        require(bundledCatalog().any { it.id == id })
        val state = readState()
        state.put(id, (state.optJSONObject(id) ?: JSONObject()).put("enabled", enabled))
        writeState(state)
        published()
    }

    /** Explicit removal disables this pack, including any bundled fallback. */
    fun remove(id: String) = synchronized(monitor) {
        require(bundledCatalog().any { it.id == id })
        val state = readState().put(id, JSONObject().put("enabled", false))
        writeState(state)
        published()
        collectUnused(state)
    }

    private fun collectUnused(state: JSONObject) {
        val keep = leases.keys.toMutableSet()
        state.keys().forEach { id ->
            state.getJSONObject(id).optJSONObject("pack")?.let(PublicResourceCatalog::pack)
                ?.files?.forEach { keep += it.sha256 }
        }
        root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) && it.name !in keep }
            .forEach(File::delete)
    }

    private fun isBundled(pack: PublicResourcePack): Boolean = pack.files.all { spec ->
        context.assets.list(spec.name.substringBeforeLast('/')).orEmpty().contains(spec.name.substringAfterLast('/'))
    }

    private fun directory(): File = root.apply { check(isDirectory || mkdirs()) }
    private fun blob(hash: String): File = File(directory(), hash)
    private fun stagingFile(): File = File.createTempFile("prepare-", ".tmp", directory())
    private fun readState(): JSONObject {
        val file = AtomicFile(File(root, "registry.json"))
        return try { file.openRead().use { JSONObject(String(readBounded(it), Charsets.UTF_8)) } }
        catch (_: java.io.FileNotFoundException) { JSONObject() }
    }

    private fun writeState(value: JSONObject) {
        val file = AtomicFile(File(directory(), "registry.json"))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private fun published() { counter.incrementAndGet(); onPublished() }
    private fun publishBlob(source: File, destination: File) {
        if (destination.isFile && destination.inputStream().use { digest(it) } == destination.name) {
            check(source.delete())
            return
        }
        check((leases[destination.name] ?: 0) == 0) { "Resource is in use" }
        if (destination.exists()) check(destination.delete())
        check(source.renameTo(destination))
    }

    private fun valid(file: File, spec: ResourceFileSpec): Boolean = file.isFile && file.length() == spec.bytes &&
        file.inputStream().use { digest(it) } == spec.sha256

    private fun copyVerified(input: InputStream, output: File, spec: ResourceFileSpec) {
        val hash = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        output.outputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                active()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= spec.bytes)
                hash.update(buffer, 0, count)
                stream.write(buffer, 0, count)
            }
        }
        require(bytes == spec.bytes && hex(hash.digest()) == spec.sha256) { "Damaged resource" }
    }

    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { active(); val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        return hex(hash.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun active() { if (Thread.currentThread().isInterrupted) throw CancellationException() }
    private fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readBytesWithLimit(PublicResourceCatalog.MAX_BYTES)
        return bytes
    }
    private fun InputStream.readBytesWithLimit(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
