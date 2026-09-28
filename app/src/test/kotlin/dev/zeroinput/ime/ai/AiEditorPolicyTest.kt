package dev.zeroinput.ime.ai

import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import org.junit.Assert.*
import org.junit.Test

class AiEditorPolicyTest {
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
