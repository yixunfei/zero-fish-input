package dev.zeroinput.ai.api

import org.junit.Assert.assertThrows
import org.junit.Test

class AiModelsTest {
    @Test
    fun requestRejectsOversizedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "x".repeat(AiLimits.MAX_INPUT_CHARS + 1))
        }
    }

    @Test
    fun policyRejectsSensitiveEditor() {
        assertThrows(AiProviderError.Policy::class.java) {
            AiGenerationPolicy(true, true, true).check(AiRequest(null, AiAction.ASK, "hello"))
        }
    }

    @Test
    fun policyRejectsDisabledNetwork() {
        assertThrows(AiProviderError.Policy::class.java) {
            AiGenerationPolicy(enabled = true, networkAllowed = false, sensitiveEditor = false)
                .check(AiRequest(null, AiAction.ASK, "hello"))
        }
    }

    @Test
    fun policyRejectsPrivacyRestrictedSession() {
        val request = AiRequest(null, AiAction.ASK, "hello")
        assertThrows(AiProviderError.Policy::class.java) {
            AiGenerationPolicy(true, true, false, personalizationAllowed = false).check(request)
        }
        assertThrows(AiProviderError.Policy::class.java) {
            AiGenerationPolicy(true, true, false, suggestionsAllowed = false).check(request)
        }
    }

    @Test
    fun conversationRejectsOversizedMessage() {
        assertThrows(IllegalArgumentException::class.java) {
            AiMessage(AiRole.USER, "x".repeat(AiLimits.MAX_MESSAGE_CHARS + 1))
        }
    }

    @Test
    fun conversationRejectsDuplicateHistoryOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            AiConversation(
                title = "title",
                messages = List(AiLimits.MAX_HISTORY_MESSAGES + 1) { AiMessage(AiRole.USER, "x") },
            )
        }
    }
}
