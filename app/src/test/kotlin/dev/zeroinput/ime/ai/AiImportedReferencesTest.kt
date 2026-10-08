package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiReference
import org.junit.Assert.*
import org.junit.Test

class AiImportedReferencesTest {
    @Test fun malformedSurrogatesRejectTheWholeImportIncludingExactChunkBoundaries() {
        for (text in listOf("x".repeat(4095) + "\uD83D", "\uD83D", "\uDE00",
            "valid\uD83Dinvalid", "x".repeat(4096) + "\uDE00")) {
            assertThrows(IllegalArgumentException::class.java) { importedReferences(text) }
        }
    }

    @Test fun supplementaryCharacterEndingExactlyAtChunkBoundaryRemainsIntact() {
        val text = "x".repeat(4094) + "\uD83D\uDE00"
        assertEquals(text, importedReferences(text).single().text)
    }

    @Test fun importSplittingPreservesSupplementaryCharactersAndCompleteText() {
        val text = "x".repeat(4095) + "\uD83D\uDE00" + "y".repeat(4096)
        val references = importedReferences(text)
        assertEquals(text, references.joinToString("") { it.text })
        assertTrue(references.all { it.text.length <= 4096 && it.source == AiReference.Source.IMPORT })
        assertFalse(references.any { it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate() })
    }
}
