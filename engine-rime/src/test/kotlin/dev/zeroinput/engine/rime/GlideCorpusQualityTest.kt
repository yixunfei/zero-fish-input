package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.GlideLayout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GlideCorpusQualityTest {
    @Test fun `production lexicons preserve public synthetic trace coverage across layouts and panel geometry`() {
        val result = GlideCorpusEvaluation.evaluate(File(".."))
        GlideCorpusEvaluation.writeReport(result, File("build/reports/glide-quality.tsv"))
        assertEquals(5, result.counts.size)
        for (layout in GlideLayout.entries) {
            val rows = result.results.filter { it.layout == layout }
            assertTrue(rows.isNotEmpty())
            assertTrue("Public synthetic top-eight coverage below threshold for $layout",
                rows.count { it.rank in 1..8 } >= rows.size * 0.9)
            for (id in rows.map { it.id }.distinct()) {
                val modes = rows.filter { it.id == id }.associateBy { it.mode }
                assertEquals(modes.getValue("center").rank, modes.getValue("compact").rank)
                assertEquals(modes.getValue("center").rank, modes.getValue("held_turn").rank)
            }
        }
    }
}
