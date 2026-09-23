package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** A fixed development/acceptance benchmark, not a held-out natural-language accuracy claim. */
class AssociationQualityTest {
    private val fixtures = resource("association-quality.tsv")
    private val cases = AssociationQualityEvaluation.readCases(fixtures.toString(Charsets.UTF_8))
    private val results = AssociationQualityEvaluation.evaluate(WordAssociationIndex.loadBundled(), cases)

    @Test fun `frozen public evaluation cases remain identical to the measured baseline`() {
        val hash = MessageDigest.getInstance("SHA-256").digest(fixtures).joinToString("") { "%02x".format(it) }
        assertEquals("5e6e564b2021733285c1b77f62cb18afd9f898a1c64654357537919b5b57e40e", hash)
        assertTrue(resource("association-quality-baseline.tsv").toString(Charsets.UTF_8)
            .startsWith("# fixture_sha256=$hash\n"))
        assertEquals(156, cases.size)
    }

    @Test fun `expansion preserves baseline hits and improves both language coverage`() {
        val baseline = resource("association-quality-baseline.tsv").toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }.drop(1).map { it.split('\t') }.associateBy { it[0] }
        for (result in results) {
            val old = baseline.getValue(result.case.id)
            val oldRank = old[5].toInt()
            if (oldRank > 0) assertTrue("Baseline hit regressed at ${result.case.id}", result.rank in 1..oldRank)
            if (result.case.expected.isEmpty()) assertEquals("Unexpected result at ${result.case.id}", 0, result.count)
        }
        for (language in InputLanguage.entries) {
            val positive = results.filter { it.case.language == language && it.case.expected.isNotEmpty() }
            val oldCovered = positive.count { baseline.getValue(it.case.id)[4].toInt() > 0 }
            assertTrue(positive.count { it.count > 0 } > oldCovered)
            val oldTopOne = positive.count { baseline.getValue(it.case.id)[5].toInt() == 1 }
            assertTrue(positive.count { it.rank == 1 } > oldTopOne)
        }
    }

    @Test fun `every public anchor stays bounded unique and reproducible`() {
        val corpus = resource("word-associations.tsv").toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }.toList()
        val index = WordAssociationIndex.loadBundled()
        val anchors = corpus.groupBy { it[0] to it[1] }
        for ((key, rows) in anchors) {
            val language = if (key.first == "zh") InputLanguage.CHINESE else InputLanguage.ENGLISH
            val words = index.suggest(language, key.second, Int.MAX_VALUE).map { it.text }
            assertTrue(words.size in 1..8)
            assertEquals(rows.map { it[2] }, words)
            assertEquals(words.distinct(), words)
        }
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/$name"))
        .use { it.readBytes() }
}
