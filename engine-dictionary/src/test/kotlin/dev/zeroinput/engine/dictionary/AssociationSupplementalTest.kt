package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A supplemental smoke set for continuation coverage. These cases exercise
 * recently added rows; they are not an independent natural-user accuracy set.
 */
class AssociationSupplementalTest {
    private val cases = readCases()
    private val results = AssociationQualityEvaluation.evaluate(WordAssociationIndex.loadBundled(), cases)

    @Test fun `supplemental contexts have an acceptable continuation in the first page`() {
        for (result in results) {
            assertTrue("No supplemental continuation for ${result.case.id}", result.rank in 1..8)
        }
    }

    @Test fun `supplemental set keeps both language groups covered`() {
        for (language in InputLanguage.entries) {
            val selected = results.filter { it.case.language == language }
            assertTrue(selected.isNotEmpty())
            assertTrue(selected.count { it.rank in 1..3 } * 2 >= selected.size)
        }
    }

    private fun readCases(): List<AssociationQualityEvaluation.Case> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/association-supplemental.tsv"))
            .bufferedReader(Charsets.UTF_8)
        return resource.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 4)
                val language = when (fields[1]) {
                    "zh" -> InputLanguage.CHINESE
                    "en" -> InputLanguage.ENGLISH
                    else -> error("Invalid supplemental language")
                }
                AssociationQualityEvaluation.Case(
                    fields[0], language, "supplemental", fields[2], fields[3].split('|').toSet(),
                )
            }.toList()
        }
    }
}
