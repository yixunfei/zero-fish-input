package dev.zeroinput.languagepack

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceFileVerifierTest {
    @Test fun `unchanged immutable blob is hashed once per process verifier`() = withFile { file ->
        var hashes = 0
        val spec = spec(file)
        val verifier = ResourceFileVerifier({ input -> hashes++; digest(input.readBytes()) }, { 1 })
        assertTrue(verifier.valid(file, spec))
        assertTrue(verifier.valid(file, spec))
        assertEquals(1, hashes)
        val restarted = ResourceFileVerifier({ input -> hashes++; digest(input.readBytes()) }, { 1 })
        assertTrue(restarted.valid(file, spec))
        assertEquals(2, hashes)
    }

    @Test fun `same length modification and restored mtime still invalidates changed identity`() = withFile { file ->
        var identity = 1
        val verifier = ResourceFileVerifier({ digest(it.readBytes()) }, { identity })
        val spec = spec(file)
        val mtime = file.lastModified()
        assertTrue(verifier.valid(file, spec))
        file.writeText("damaged")
        file.setLastModified(mtime)
        identity++
        assertFalse(verifier.valid(file, spec))
    }

    @Test fun `unsupported identity always retains full hashing`() = withFile { file ->
        var hashes = 0
        val spec = spec(file)
        val verifier = ResourceFileVerifier({ hashes++; digest(it.readBytes()) }, { null })
        assertTrue(verifier.valid(file, spec))
        assertTrue(verifier.valid(file, spec))
        assertEquals(2, hashes)
        file.writeText("damaged")
        assertFalse(verifier.valid(file, spec))
    }

    @Test fun `filesystem identity never trusts size and restored modification time alone`() = withFile { file ->
        val verifier = ResourceFileVerifier({ digest(it.readBytes()) })
        val spec = spec(file)
        val mtime = file.lastModified()
        assertTrue(verifier.valid(file, spec))
        file.writeText("damaged")
        file.setLastModified(mtime)
        assertFalse(verifier.valid(file, spec))
    }

    @Test fun `identity changing during hashing does not publish a verified ticket`() = withFile { file ->
        var identity = 1
        val spec = spec(file)
        val verifier = ResourceFileVerifier({ identity++; digest(it.readBytes()) }, { identity })
        assertFalse(verifier.valid(file, spec))
        assertFalse(verifier.valid(file, spec))
    }

    private fun spec(file: File) = ResourceFileSpec("fixture", file.length(), digest(file.readBytes()))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun withFile(test: (File) -> Unit) {
        val root = Files.createTempDirectory("resource-verify").toFile()
        try { test(File(root, "blob").apply { writeText("content") }) } finally { root.deleteRecursively() }
    }
}
