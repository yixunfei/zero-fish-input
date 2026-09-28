package dev.zeroinput.ime.ai

import dev.zeroinput.ime.core.privacy.SessionPrivacy

internal object AiEditorPolicy {
    fun allows(privacy: SessionPrivacy): Boolean = !privacy.isSensitive &&
        privacy.personalizationAllowed && privacy.suggestionsAllowed && privacy.predictionsAllowed
}
