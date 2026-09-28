package dev.zeroinput.engine.rime

import org.junit.Assert.*
import org.junit.Test

class RelatedReadingsTest {
    private val readings = RelatedReadings(
        listOf("neng", "nen", "leng", "nen", "xi", "an", "xian", "ni", "li", "hao", "gong", "gon"),
    )
    @Test fun `valid alternative segmentation precedes adjacent readings`() {
        assertEquals(listOf("xi'an"), readings.alternatives("xian"))
        assertTrue("leng" in readings.alternatives("neng"))
        assertTrue("li'hao" in readings.alternatives("nihao"))
        assertTrue("gon" in readings.alternatives("gong"))
    }
    @Test fun `invalid and excessively ambiguous inputs have bounded expansion`() {
        assertTrue(readings.alternatives("64").isEmpty())
        assertTrue(readings.alternatives("ni".repeat(40)).isEmpty())
        assertTrue(readings.alternatives("ni".repeat(16)).size <= 32)
        assertEquals(readings.alternatives("neng").distinct(), readings.alternatives("neng"))
    }
}
