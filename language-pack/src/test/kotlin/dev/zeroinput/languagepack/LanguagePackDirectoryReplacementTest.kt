package dev.zeroinput.languagepack

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePackDirectoryReplacementTest {
    @Test fun `startup recovers a moved original and removes a superseded backup`() = withDirectories { root ->
        val moved = File(root, ".v1.backup-123").apply { mkdir() }
        File(moved, "dictionary.txt").writeText("original")
        val superseded = File(root, ".v2.backup-456").apply { mkdir() }
        val active = File(root, "v2").apply { mkdir() }
        File(active, "dictionary.txt").writeText("replacement")
        val unrelated = File(root, ".keep").apply { mkdir() }

        LanguagePackDirectoryReplacement.recover(root)

        assertEquals("original", File(root, "v1/dictionary.txt").readText())
        assertEquals("replacement", File(active, "dictionary.txt").readText())
        assertFalse(moved.exists())
        assertFalse(superseded.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun `failure moving the installed directory preserves the original package`() = withDirectories { root ->
        val destination = FailingRename(File(root, "installed").path).apply { mkdir() }
        File(destination, "dictionary.txt").writeText("original")
        val staging = File(root, "staging").apply { mkdir() }
        File(staging, "dictionary.txt").writeText("replacement")

        assertThrows(IllegalArgumentException::class.java) {
            LanguagePackDirectoryReplacement.replace(staging, destination)
        }

        assertEquals("original", File(destination, "dictionary.txt").readText())
        assertEquals("replacement", File(staging, "dictionary.txt").readText())
        assertEquals(2, checkNotNull(root.listFiles()).size)
    }

    @Test fun `failed activation restores the previous package`() = withDirectories { root ->
        val destination = File(root, "installed").apply { mkdir() }
        File(destination, "dictionary.txt").writeText("original")
        val staging = FailingRename(File(root, "staging").path).apply { mkdir() }
        File(staging, "dictionary.txt").writeText("replacement")

        assertThrows(IllegalArgumentException::class.java) {
            LanguagePackDirectoryReplacement.replace(staging, destination)
        }

        assertEquals("original", File(destination, "dictionary.txt").readText())
        assertEquals("replacement", File(staging, "dictionary.txt").readText())
        assertEquals(2, checkNotNull(root.listFiles()).size)
    }

    @Test fun `successful replacement activates the new package and removes the backup`() = withDirectories { root ->
        val destination = File(root, "installed").apply { mkdir() }
        File(destination, "dictionary.txt").writeText("original")
        val staging = File(root, "staging").apply { mkdir() }
        File(staging, "dictionary.txt").writeText("replacement")

        LanguagePackDirectoryReplacement.replace(staging, destination)

        assertEquals("replacement", File(destination, "dictionary.txt").readText())
        assertFalse(staging.exists())
        assertEquals(listOf(destination), root.listFiles().orEmpty().toList())
    }

    private fun withDirectories(test: (File) -> Unit) {
        val root = Files.createTempDirectory("pack-replacement").toFile()
        try { test(root) } finally { assertTrue(root.deleteRecursively()) }
    }

    private class FailingRename(path: String) : File(path) {
        override fun renameTo(dest: File): Boolean = false
    }
}
