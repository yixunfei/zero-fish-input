package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Public scene coverage, curated with visible labels; no natural-input accuracy claim. */
class AssociationScenarioQualityTest {
    private val fixtures = resource("association-quality-scenarios.tsv")
    private val cases = AssociationQualityEvaluation.readCases(fixtures.toString(Charsets.UTF_8))
    private val results = AssociationQualityEvaluation.evaluate(WordAssociationIndex.loadBundled(), cases)

    @Test fun `scenario labels and before measurements remain frozen`() {
        assertEquals("756d14adac9942fce842d89070887ffa9e9efb91f043798387f637d6d8354bf6", sha256(fixtures))
        assertEquals("801c24764b51b8b717015f5eb8c5b1857314a5b057d4a1b39a5350c71def2f0e",
            sha256(resource("association-quality-scenarios-baseline.tsv")))
        assertEquals(100, cases.size)
        for (language in InputLanguage.entries) {
            val selected = cases.filter { it.language == language }
            assertEquals(40, selected.count { it.expected.isNotEmpty() })
            assertEquals(10, selected.count { it.expected.isEmpty() })
            assertEquals(10, selected.filter { it.expected.isNotEmpty() }.map { it.category }.distinct().size)
        }
    }

    @Test fun `every positive scene has a top three continuation while negatives remain empty`() {
        val baseline = resource("association-quality-scenarios-baseline.tsv").toString(Charsets.UTF_8)
            .lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.drop(1)
            .map { it.split('\t') }.associateBy { it[0] }
        for (result in results) {
            if (result.case.expected.isEmpty()) assertEquals("Abstention ${result.case.id}", 0, result.count)
            else {
                assertTrue("Scene ${result.case.id}", result.rank in 1..3)
                val previousRank = baseline.getValue(result.case.id)[5].toInt()
                if (previousRank > 0) assertTrue("Previous hit ${result.case.id}", result.rank <= previousRank)
            }
        }
        for (language in InputLanguage.entries) {
            val positive = results.filter { it.case.language == language && it.case.expected.isNotEmpty() }
            assertTrue(positive.count { it.rank == 1 } >= 38)
        }
    }

    @Test fun `scene continuations form complete selection chains with correct separators`() {
        val index = WordAssociationIndex.loadBundled()
        val chains = listOf(
            Triple(InputLanguage.CHINESE, "收到请", listOf("确认", "时间")),
            Triple(InputLanguage.CHINESE, "文档已经", listOf("更新", "完成")),
            Triple(InputLanguage.CHINESE, "会议改到", listOf("明天", "下午")),
            Triple(InputLanguage.CHINESE, "收到請", listOf("確認", "時間")),
            Triple(InputLanguage.ENGLISH, "can we meet", listOf("tomorrow", "morning")),
            Triple(InputLanguage.ENGLISH, "how do i get", listOf("to", "the", "station")),
            Triple(InputLanguage.ENGLISH, "please review ", listOf("the", "document")),
            Triple(InputLanguage.ENGLISH, "the file has been", listOf("saved", "successfully")),
        )
        for ((language, initial, words) in chains) {
            var context = initial
            for (word in words) {
                val suggestion = index.suggest(language, context.takeLast(32), 8).firstOrNull { it.text == word }
                assertNotNull("Missing chain continuation for $language", suggestion)
                context += checkNotNull(suggestion).commitText
            }
            val separator = if (language == InputLanguage.ENGLISH) " " else ""
            assertEquals(initial.trimEnd() + separator + words.joinToString(separator), context)
        }
    }

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
