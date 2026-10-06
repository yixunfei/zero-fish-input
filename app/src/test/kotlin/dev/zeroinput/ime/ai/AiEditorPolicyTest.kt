package dev.zeroinput.ime.ai

import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import org.junit.Assert.*
import org.junit.Test

class AiEditorPolicyTest {
    @Test fun noSuggestionsChatAllowsIndependentAiWithoutPersonalization() {
        val editor = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val policy = dev.zeroinput.ime.core.privacy.EditorPrivacyPolicy()
        val ordinary = policy.evaluate(editor, dev.zeroinput.ime.core.privacy.PrivacyConfiguration())
        assertTrue(AiEditorPolicy.allows(ordinary))
        assertFalse(ordinary.personalizationAllowed)
        assertFalse(ordinary.predictionsAllowed)
        for (configuration in listOf(
            dev.zeroinput.ime.core.privacy.PrivacyConfiguration(learningEnabled = false),
            dev.zeroinput.ime.core.privacy.PrivacyConfiguration(incognitoMode = true),
        )) assertFalse(AiEditorPolicy.allows(policy.evaluate(editor, configuration)))
        editor.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        assertFalse(AiEditorPolicy.allows(policy.evaluate(editor,
            dev.zeroinput.ime.core.privacy.PrivacyConfiguration())))
        editor.imeOptions = 0
        for (variation in listOf(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            android.text.InputType.TYPE_TEXT_VARIATION_URI, android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, 0x0ff0)) {
            editor.inputType = android.text.InputType.TYPE_CLASS_TEXT or variation or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            assertFalse(AiEditorPolicy.allows(policy.evaluate(editor,
                dev.zeroinput.ime.core.privacy.PrivacyConfiguration())))
        }
    }

    @Test fun allRestrictedPrivacyReasonsRejectAiEvenWithPublicConversionEnabled() {
        for (reason in PrivacyReason.entries.filter { it != PrivacyReason.NONE }) {
            assertFalse(AiEditorPolicy.allows(SessionPrivacy(false, true, false, reason)))
        }
        assertFalse(AiEditorPolicy.allows(SessionPrivacy(true, true, true, PrivacyReason.PASSWORD_FIELD)))
        assertFalse(AiEditorPolicy.allows(SessionPrivacy(false, false, true, PrivacyReason.EDITOR_REQUEST)))
        assertFalse(AiEditorPolicy.allows(SessionPrivacy(false, true, true, PrivacyReason.EDITOR_REQUEST, false)))
        assertTrue(AiEditorPolicy.allows(SessionPrivacy(false, true, true, PrivacyReason.NONE)))
    }
}
