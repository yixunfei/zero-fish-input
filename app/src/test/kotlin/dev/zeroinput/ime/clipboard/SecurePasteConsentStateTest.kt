package dev.zeroinput.ime.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePasteConsentStateTest {
    @Test fun preauthorizationExpiresAtThirtySecondsEvenWithoutTimerDelivery() {
        var now = 0L
        val consent = SecurePasteConsent("fixture", source, 7) { now }
        now = 29_999
        assertTrue(consent.leaveEditor())
        now = 30_000
        assertFalse(consent.leaveEditor())
        assertFalse(consent.bind(1, source))
    }

    private val source = PasteEditorIdentity("public.fixture", 7, 1)

    @Test fun credentialSelectionBeforeReturnDoesNotCancelAuthenticationNavigation() {
        val consent = request()
        assertTrue(consent.editorChanged(source.copy(packageName = "credential.fixture", inputType = 18)))
        assertTrue(consent.bind(2, source))
    }

    @Test fun externalSourceEditBeforeReturnBindingRevokesPendingConsent() {
        val consent = request()
        assertFalse(consent.editorChanged(source))
        assertFalse(consent.bind(2, source))
    }

    @Test fun externalSelectionChangeAfterBindingRevokesEvenTheSamePublicEditor() {
        val consent = request()
        assertTrue(consent.bind(2, source))
        assertFalse(consent.editorChanged(source))
        assertFalse(consent.bind(2, source))
    }

    @Test fun losingEditorIdentityAfterBindingRevokesPendingConsent() {
        val consent = request()
        assertTrue(consent.bind(2, source))
        assertFalse(consent.editorChanged(null))
        assertFalse(consent.bind(2, source))
    }

    private fun request() = SecurePasteConsent("fixture", source, 7) { 0L }
}
