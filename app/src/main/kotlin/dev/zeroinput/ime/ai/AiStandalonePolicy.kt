package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiGenerationPolicy
import dev.zeroinput.userdata.AiConfiguration

/** An explicit user-authored surface with no editor or personalization read ports. */
internal object AiStandalonePolicy {
    fun allows(learningEnabled: Boolean, incognito: Boolean, unlocked: Boolean): Boolean =
        learningEnabled && !incognito && unlocked

    fun generationPolicy(config: AiConfiguration, allowed: Boolean) = AiGenerationPolicy(
        enabled = config.enabled,
        networkAllowed = config.networkAllowed,
        sensitiveEditor = false,
        personalizationAllowed = false,
        suggestionsAllowed = allowed,
        independentAiAllowed = allowed,
    )
}
