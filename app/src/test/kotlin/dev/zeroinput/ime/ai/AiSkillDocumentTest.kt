package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiSkill
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSkillDocumentTest {
    @Test fun plainTextRequiresUserNamingAndMetadataPreservesLiteralInstructions() {
        val plain = AiSkillDocument.parse("# Public recipe\nSummarize the supplied text.")
        assertEquals("", plain.name)
        assertTrue(plain.tools.isEmpty())
        val parsed = AiSkillDocument.parse("\uFEFF---\r\nname: \"Public recipe\"\r\ndescription: 'Public fixture'\r\nallowed-tools: [\"files/read\"]\r\n---\r\nUse the reviewed file.")
        assertEquals("Public recipe", parsed.name)
        assertEquals("Public fixture", parsed.description)
        assertEquals(listOf("files/read"), parsed.tools)
        assertEquals("Use the reviewed file.", parsed.instructions)
    }

    @Test fun malformedAndExecutableMetadataIsRejectedWithoutAnInterpreter() {
        for (metadata in listOf("name: Public\nname: Duplicate", "name: Public\ncommand: run.exe",
            "name: &anchor Public", "name: *anchor", "name: |\n  Public", "name: Public\nunknown: value",
            "name: Public\nallowed-tools: [1]", "name: Public\nallowed-tools: [\"read\",\"read\"]",
            "name: Public\nallowed-tools: [\"read\"] trailing", "name: Public\nallowed-tools: [\"../bad tool\"]",
            "name: Public\nallowed-tools: [\"read\",]", "name: Public\nallowed-tools: [unquoted]",
            "name: Public\nallowed-tools: " + "[".repeat(500) + "]".repeat(500))) {
            assertTrue(runCatching { AiSkillDocument.parse("---\n$metadata\n---\nPublic instructions") }.isFailure)
        }
        for (text in listOf("", "\u0000", "x".repeat(AiSkill.MAX_BYTES + 1), "中".repeat(AiSkill.MAX_BYTES / 3 + 1), "---\nname: Public")) {
            assertTrue(runCatching { AiSkillDocument.parse(text) }.isFailure)
        }
    }
}
