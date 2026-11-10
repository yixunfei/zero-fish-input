package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiSkillTest {
    @Test fun toolRequirementsAreImmutableAndCannotGrantChatCapabilities() {
        val tools = mutableListOf("files/read")
        val skill = AiSkill("id", "Public", "", "Read selected file", tools)
        tools.clear()
        assertFalse(skill.supportedBy(emptySet()))
        assertTrue(skill.supportedBy(setOf("files/read")))
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "Public", workspaceContext = AiWorkspaceContext(skills = listOf(skill)))
        }
        assertFalse(skill.toString().contains("Read selected"))
    }

    @Test fun skillsCountAgainstTheSharedContextBudgetAndDuplicateIdsFailClosed() {
        val skill = AiSkill("id", "Public", "", "x".repeat(16_000))
        assertThrows(IllegalArgumentException::class.java) { AiSkillLibrary(skills = listOf(skill, skill)) }
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "Public", references = listOf(AiReference("x".repeat(500))),
                workspaceContext = AiWorkspaceContext(skills = listOf(skill)))
        }
        assertThrows(IllegalArgumentException::class.java) { AiSkill("id", "Public", "", "中".repeat(6_000)) }
    }
}
