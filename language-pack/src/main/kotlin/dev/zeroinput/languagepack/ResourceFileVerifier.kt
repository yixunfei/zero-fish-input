package dev.zeroinput.languagepack

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/** Process-local verification of immutable blobs. No metadata ticket survives process death. */
internal class ResourceFileVerifier(
    private val digest: (InputStream) -> String,
    private val identity: (File) -> Any? = ::resourceIdentity,
) {
    private data class Verified(val identity: Any, val bytes: Long, val sha256: String)
    private val verified = LinkedHashMap<String, Verified>()

    fun valid(file: File, spec: ResourceFileSpec): Boolean {
        if (!file.isFile || file.length() != spec.bytes) {
            invalidate(file)
            return false
        }
        val before = identity(file)
        val cached = verified[file.absolutePath]
        if (before != null && cached == Verified(before, spec.bytes, spec.sha256)) return true
        invalidate(file)
        if (file.inputStream().use(digest) != spec.sha256) return false
        val after = identity(file)
        if (before != null && before == after) {
            if (verified.size >= MAX_VERIFIED_FILES) verified.remove(verified.keys.first())
            verified[file.absolutePath] = Verified(before, spec.bytes, spec.sha256)
        }
        return before == after
    }

    fun invalidate(file: File) { verified.remove(file.absolutePath) }

    private companion object { const val MAX_VERIFIED_FILES = 64 }
}

private fun resourceIdentity(file: File): Any? = try {
    // ctime detects same-size writes even when mtime is restored. If the filesystem
    // cannot provide this identity, every acquisition retains full hash verification.
    Files.readAttributes(file.toPath(), "unix:dev,ino,size,lastModifiedTime,ctime", LinkOption.NOFOLLOW_LINKS)
} catch (_: UnsupportedOperationException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: java.io.IOException) {
    null
}
