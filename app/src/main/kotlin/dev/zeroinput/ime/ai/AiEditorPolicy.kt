package dev.zeroinput.ime.ai

import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ai.api.AiGenerationPolicy

internal object AiEditorPolicy {
    fun allows(privacy: SessionPrivacy): Boolean = !privacy.isSensitive &&
        privacy.suggestionsAllowed && privacy.independentAiAllowed

    fun generationPolicy(enabled: Boolean, networkAllowed: Boolean, privacy: SessionPrivacy?) = AiGenerationPolicy(
        enabled = enabled,
        networkAllowed = networkAllowed,
        sensitiveEditor = privacy?.isSensitive ?: true,
        personalizationAllowed = privacy?.personalizationAllowed == true,
        suggestionsAllowed = privacy?.suggestionsAllowed == true,
        independentAiAllowed = privacy?.independentAiAllowed == true,
    )
}
