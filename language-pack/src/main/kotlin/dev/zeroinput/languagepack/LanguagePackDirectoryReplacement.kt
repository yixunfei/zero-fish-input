package dev.zeroinput.languagepack

import java.io.File

/** Owns activation rollback without touching the original until it has been moved safely. */
internal object LanguagePackDirectoryReplacement {
    private val backupName = Regex("\\.([A-Za-z0-9][A-Za-z0-9._-]{0,63})\\.backup--?[0-9]+")

    fun recover(parent: File) {
        parent.listFiles().orEmpty().filter { it.isDirectory }
            .sortedByDescending { it.lastModified() }
            .forEach { backup ->
                val version = backupName.matchEntire(backup.name)?.groupValues?.get(1) ?: return@forEach
                val destination = File(parent, version)
                if (!destination.exists()) {
                    check(backup.renameTo(destination)) { "Unable to restore the previous language pack" }
                } else if (destination.isDirectory) {
                    backup.deleteRecursively()
                }
            }
    }

    fun replace(staging: File, destination: File) {
        destination.parentFile?.mkdirs()
        val backup = File(destination.parentFile, ".${destination.name}.backup-${System.nanoTime()}")
        var oldMoved = false
        try {
            if (destination.exists()) {
                require(destination.renameTo(backup)) { "Unable to stage the existing language pack" }
                oldMoved = true
            }
            require(staging.renameTo(destination)) { "Unable to activate language pack" }
        } catch (error: Throwable) {
            // A failed first rename leaves the original at destination; it is not rollback debris.
            if (oldMoved && backup.exists() && !backup.renameTo(destination)) {
                error.addSuppressed(IllegalStateException("Unable to restore the previous language pack"))
            }
            throw error
        }
        // Cleanup failure must never roll back a successfully activated package using a partial backup.
        if (oldMoved) runCatching { backup.deleteRecursively() }
    }
}
