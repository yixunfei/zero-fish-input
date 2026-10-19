package dev.zeroinput.languagepack

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePackEnabledStateTest {
    @Test fun `changing one flag after startup corruption keeps other packages disabled after restart`() = withFile { file ->
        file.writeText("{broken")
        val keys = setOf("first@1", "second@1", "third@1")
        val state = LanguagePackEnabledState(file) { keys }
        val current = state.read()
        assertTrue(state.invalid)
        current["first@1"] = true
        state.write(current)

        val restarted = LanguagePackEnabledState(file) { keys }
        assertEquals(mapOf("first@1" to true, "second@1" to false, "third@1" to false), restarted.read())
        assertFalse(restarted.invalid)
    }

    @Test fun `recovery preserves last verified flags without enabling unknown packages`() = withFile { file ->
        file.writeText("{\"first@1\":true,\"second@1\":false}")
        val state = LanguagePackEnabledState(file) { setOf("first@1", "second@1", "new@1") }
        state.read()
        file.writeText("{broken")
        val current = state.read()
        current["second@1"] = true
        state.write(current)
        assertEquals(mapOf("first@1" to true, "second@1" to true, "new@1" to false), state.read())
    }

    @Test fun `non boolean flags are treated as corrupt instead of coerced`() = withFile { file ->
        file.writeText("{\"first@1\":\"true\"}")
        val state = LanguagePackEnabledState(file) { setOf("first@1") }
        assertTrue(state.read().isEmpty())
        assertTrue(state.invalid)
    }

    private fun withFile(test: (File) -> Unit) {
        val root = Files.createTempDirectory("pack-state").toFile()
        try { test(File(root, "enabled.json")) } finally { root.deleteRecursively() }
    }
}
