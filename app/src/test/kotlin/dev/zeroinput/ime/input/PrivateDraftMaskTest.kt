package dev.zeroinput.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateDraftMaskTest {
    @Test fun shortDraftsNeverExposeAnyCommittedCharacter() {
        for (text in listOf("", "a", "中文", "公开样本文本", "🌏a", "x".repeat(64))) {
            assertEquals("•".repeat(text.length.coerceAtMost(32)), maskedPrivateDraft(text))
        }
    }
}
