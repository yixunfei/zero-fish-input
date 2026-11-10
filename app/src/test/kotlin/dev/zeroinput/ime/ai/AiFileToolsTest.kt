package dev.zeroinput.ime.ai

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiFileToolsTest {
    @Test fun catalogUsesOnlySelectedFileIdsAndCannotAcceptUrisOrUnknownParameters() {
        val tool = AiFileTools.definitions(listOf("file1")).first()
        val schema = AiToolJson.schema(tool.schema)
        assertEquals("file1", AiToolJson.arguments(schema, """{"file":"file1","offset":0,"length":20}""").getString("file"))
        for (args in listOf("""{"file":"content://private","offset":0,"length":20}""",
            """{"file":"file2","offset":0,"length":20}""", """{"file":"file1","offset":-1,"length":20}""",
            """{"file":"file1","offset":0,"length":3501}""", """{"file":"file1","offset":0.5,"length":20}""")) {
            assertTrue(runCatching { AiToolJson.arguments(schema, args) }.isFailure)
        }
        assertEquals(listOf(AiFileTools.SAVE), AiFileTools.definitions(emptyList()).map { it.name })
    }
    @Test fun literalSearchAndExcerptsAreBoundedAndOutputNamesCannotTraverse() {
        assertTrue(AiFileTools.excerpt("Public text", 0, 6).endsWith("Public"))
        assertTrue(AiFileTools.search("PUBLIC public", "public", true).contains("Matches shown: 2"))
        assertEquals("No literal matches.", AiFileTools.search("public", ".*", false))
        assertTrue(AiFileTools.search("public ".repeat(8_000), "public", true).length <= 4_096)
        assertThrows(IllegalArgumentException::class.java) { AiFileTools.excerpt("public", 99, 1) }
        for (name in listOf("../private.txt", "C:\\private.txt", "/private.txt", "a.exe", "a.txt ", "a\u0000.txt"))
            assertTrue(runCatching { AiFileTools.mimeForName(name) }.isFailure)
        assertEquals("text/markdown", AiFileTools.mimeForName("Public.md"))
    }
}
