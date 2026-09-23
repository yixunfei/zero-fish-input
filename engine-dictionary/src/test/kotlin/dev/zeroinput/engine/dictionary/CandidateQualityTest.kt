package dev.zeroinput.engine.dictionary

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** A fixed development/acceptance benchmark for candidate generation, not a natural-input accuracy claim. */
class CandidateQualityTest {
    private val fixtures = resource("candidate-quality.tsv")
    private val cases = CandidateQualityEvaluation.readCases(fixtures.toString(Charsets.UTF_8))
    private val results = CandidateQualityEvaluation.evaluate(ReferenceDictionary.load(), cases)

    @Test fun `frozen public evaluation cases remain identical to the recorded baseline`() {
        val hash = MessageDigest.getInstance("SHA-256").digest(fixtures).joinToString("") { "%02x".format(it) }
        assertEquals("5b60f8e1dde2928452f5e79d84055e294686c19f480b71c084dcdde197dabdb0", hash)
        assertTrue(resource("candidate-quality-baseline.tsv").toString(Charsets.UTF_8)
            .startsWith("# fixture_sha256=$hash\n"))
        assertEquals(30, cases.size)
    }

    @Test fun `reference dictionary preserves every recorded baseline result exactly`() {
        val baseline = resource("candidate-quality-baseline.tsv").toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }.drop(1).map { it.split('\t') }.associateBy { it[0] }
        assertEquals(cases.size, baseline.size)
        for (result in results) {
            val old = baseline.getValue(result.case.id)
            assertEquals("Returned count changed at ${result.case.id}", old[4].toInt(), result.count)
            assertEquals("First acceptable rank regressed at ${result.case.id}", old[5].toInt(), result.rank)
            assertEquals("Acceptable count changed at ${result.case.id}", old[6].toInt(), result.acceptable)
            if (result.case.expected.isEmpty()) assertEquals("Unexpected candidates at ${result.case.id}", 0, result.count)
        }
    }

    @Test fun `every positive reading keeps an acceptable candidate inside the display bound`() {
        val positive = results.filter { it.case.expected.isNotEmpty() }
        assertEquals(24, positive.size)
        for (result in positive) assertTrue("Not covered at ${result.case.id}", result.rank in 1..8)
    }

    @Test fun `lookup results stay bounded unique and apostrophe-insensitive`() {
        val dictionary = ReferenceDictionary.load()
        for (case in cases) {
            val words = dictionary.lookup(case.reading)
            assertEquals("Duplicate candidates for ${case.id}", words.distinct().size, words.size)
            assertTrue(words.all { it.isNotBlank() })
        }
        assertEquals(dictionary.lookup("nihao"), dictionary.lookup("ni'hao"))
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/$name"))
        .use { it.readBytes() }
}
