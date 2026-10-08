package dev.zeroinput.ime.ui

import org.junit.Assert.*
import org.junit.Test

class AiReferencePreviewTest {
    @Test fun truncationPreservesSupplementaryCharactersAndSignalsOmittedContent() {
        assertEquals("x".repeat(159) + "…", referencePreview("x".repeat(159) + "😀tail"))
        assertEquals("x".repeat(158) + "😀…", referencePreview("x".repeat(158) + "😀tail"))
    }

    @Test fun shortTextKeepsContentAndUsesOneLine() {
        assertEquals("public fixture", referencePreview("public\nfixture"))
        assertEquals("", referencePreview(""))
    }
}
