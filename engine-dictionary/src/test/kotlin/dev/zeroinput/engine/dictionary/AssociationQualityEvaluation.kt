package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import java.io.File
import java.security.MessageDigest

/** Developer-only evaluation of frozen public fixtures using the production predictor. */
object AssociationQualityEvaluation {
    data class Case(val id: String, val language: InputLanguage, val category: String,
        val context: String, val expected: Set<String>)
    data class Result(val case: Case, val count: Int, val rank: Int, val acceptable: Int)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val index = WordAssociationIndex.loadBundled()
        val bytes = checkNotNull(javaClass.getResourceAsStream("/association-quality.tsv")).use { it.readBytes() }
        val results = evaluate(index, readCases(bytes.toString(Charsets.UTF_8)))
        val output = File(args[0]).apply { parentFile.mkdirs() }
        output.writeText(buildString {
            appendLine("# fixture_sha256=${sha256(bytes)}")
            val data = checkNotNull(AssociationQualityEvaluation::class.java.getResourceAsStream("/word-associations.tsv"))
                .use { it.readBytes() }
            appendLine("# corpus_sha256=${sha256(data)}")
            appendLine("id\tlanguage\tcategory\tpositive\treturned\tfirst_acceptable_rank\tacceptable_returned")
            for (result in results) with(result) {
                appendLine("${case.id}\t${case.language.name}\t${case.category}\t${case.expected.isNotEmpty()}\t$count\t$rank\t$acceptable")
            }
        }, Charsets.UTF_8)
        for (language in InputLanguage.entries) {
            val selected = results.filter { it.case.language == language }
            val positive = selected.filter { it.case.expected.isNotEmpty() }
            val negative = selected.filter { it.case.expected.isEmpty() }
            println("${language.name}: positive=${positive.size}, covered=${positive.count { it.count > 0 }}, " +
                "top1=${positive.count { it.rank == 1 }}, top3=${positive.count { it.rank in 1..3 }}, " +
                "top8=${positive.count { it.rank in 1..8 }}, " +
                "abstention_false_positives=${negative.count { it.count > 0 }}/${negative.size}")
        }
    }

    fun readCases(text: String): List<Case> {
        val cases = text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 5)
            require(fields[0].matches(Regex("[a-z]{2}[0-9]{3}")))
            val language = when (fields[1]) {
                "zh" -> InputLanguage.CHINESE
                "en" -> InputLanguage.ENGLISH
                else -> error("Invalid fixture language")
            }
            Case(fields[0], language, fields[2], fields[3],
                if (fields[4] == "-") emptySet() else fields[4].split('|').toSet())
        }.toList()
        require(cases.size == cases.map { it.id }.distinct().size)
        return cases
    }

    fun evaluate(index: WordAssociationIndex, cases: List<Case>): List<Result> = cases.map { case ->
        val words = index.suggest(case.language, case.context, 8).map { it.text }
        val rank = words.indexOfFirst { it in case.expected }.let { if (it < 0) 0 else it + 1 }
        Result(case, words.size, rank, words.count { it in case.expected })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
