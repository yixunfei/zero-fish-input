package dev.zeroinput.ime.input

import dev.zeroinput.ime.textstego.StegoPastePolicy
import dev.zeroinput.ime.ui.StegoField
import org.junit.Assert.*
import org.junit.Test

class ExactUtf8Test {
    @Test fun exactByteBudgetAcceptsMixedUnicodeAndRejectsUnpairedSurrogates() {
        for (value in listOf("", "ascii", "汉".repeat(682) + "ab", "𠀀😀e\u0301\r\n"))
            assertEquals(value.toByteArray(Charsets.UTF_8).size, exactUtf8ByteLength(value))
        for (value in listOf("\ud800", "x\udfff", "\ud800x", "\ud800\ud800")) {
            assertNull(exactUtf8ByteLength(value))
            assertFalse(StegoPastePolicy.accepts(StegoField.SECRET, value))
        }
        assertTrue(StegoPastePolicy.accepts(StegoField.SECRET, "汉".repeat(682) + "ab"))
        assertFalse(StegoPastePolicy.accepts(StegoField.SECRET, "汉".repeat(683)))
        assertTrue(StegoPastePolicy.accepts(StegoField.COVER, "汉".repeat(512)))
        assertFalse(StegoPastePolicy.accepts(StegoField.COVER, "汉".repeat(512) + "a"))
    }
}
