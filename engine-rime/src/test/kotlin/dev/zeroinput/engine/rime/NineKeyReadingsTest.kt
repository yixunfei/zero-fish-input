package dev.zeroinput.engine.rime

import org.junit.Assert.*
import org.junit.Test

class NineKeyReadingsTest {
    private val readings = NineKeyReadings(listOf("ni", "mi", "hao", "han", "gang", "shi", "si", "zhong"))

    @Test fun `numeric input offers valid first syllables ranked by native comments`() {
        val choices = readings.choices("64426", arrayOf("ni hao", "mi han"))
        assertEquals(listOf("ni", "mi"), choices.take(2))
        assertFalse("hao" in choices)
        assertEquals("hao", readings.choices("ni'426", arrayOf("ni hao")).first())
        assertTrue(readings.choices("ni'hao'", emptyArray()).isEmpty())
    }

    @Test fun `reading choice narrows one syllable and preserves the remainder`() {
        assertEquals("ni'426", readings.replace("64426", "ni"))
        assertEquals("ni'hao'", readings.replace("ni'426", "hao"))
        assertEquals("hao'", readings.replace("42", "hao"))
        assertNull(readings.replace("64", "hao"))
        assertNull(readings.replace("64", "NI"))
        assertNull(readings.replace("64", "nonsense"))
        assertNull(readings.replace("64", ""))
        assertNull(readings.replace("6".repeat(129), "ni"))
    }

    @Test fun `all standard telephone groups can be decoded without a runtime dictionary scan`() {
        val table = NineKeyReadings(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"))
        for ((code, word) in listOf("222" to "abc", "333" to "def", "444" to "ghi", "555" to "jkl",
            "666" to "mno", "7777" to "pqrs", "888" to "tuv", "9999" to "wxyz")) {
            assertTrue(word in table.choices(code, emptyArray()))
        }
    }
}
