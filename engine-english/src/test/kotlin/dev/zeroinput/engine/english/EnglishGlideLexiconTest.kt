package dev.zeroinput.engine.english

import dev.zeroinput.engine.api.GlideLayout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class EnglishGlideLexiconTest {
    @Test fun `bundled pinned spellings extend seed with ordinary and specialist words`() {
        val rows = EnglishGlideLexicon.loadBundled()
        assertTrue(rows.size in 124_000..125_000)
        assertTrue(rows.all { it.layout == GlideLayout.ENGLISH_QWERTY })
        val index = rows.associateBy { it.inputCode }
        for (word in listOf("hello", "privacy", "handwriting", "rainforest", "astronomy", "photosynthesis")) {
            assertTrue("Missing public fixture: $word", word in index)
        }
        assertTrue(index.getValue("the").frequency > index.getValue("photosynthesis").frequency)
        assertEquals(rows.size, index.size)
    }

    @Test fun `cancelled public loading publishes no partial lexicon`() {
        assertThrows(CancellationException::class.java) { EnglishGlideLexicon.loadBundled { true } }
        var checks = 0
        assertThrows(CancellationException::class.java) { EnglishGlideLexicon.loadBundled { ++checks > 20 } }
    }
}
