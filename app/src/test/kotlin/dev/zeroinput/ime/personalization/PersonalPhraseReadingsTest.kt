package dev.zeroinput.ime.personalization

import org.junit.Assert.*
import org.junit.Test

class PersonalPhraseReadingsTest {
    @Test fun `full partial and initial pinyin find words stored under short codes`() {
        val readings = mapOf("测" to listOf("ce"), "试" to listOf("shi"), "重" to listOf("zhong", "chong"))
        for (query in listOf("ceshi", "ce", "ces", "cs", "cshi")) {
            assertTrue(PersonalPhraseReadings.matchesReadings("测试", query, readings))
        }
        assertFalse(PersonalPhraseReadings.matchesReadings("测试", "ceshiz", readings))
        assertFalse(PersonalPhraseReadings.matchesReadings("测试", "chansheng", readings))
        assertTrue(PersonalPhraseReadings.matchesReadings("重试", "chongshi", readings))
        assertTrue(PersonalPhraseReadings.matchesReadings("重试", "zs", readings))
    }
}
