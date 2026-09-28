package dev.zeroinput.languagepack

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePackDirectoryReplacementTest {
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
