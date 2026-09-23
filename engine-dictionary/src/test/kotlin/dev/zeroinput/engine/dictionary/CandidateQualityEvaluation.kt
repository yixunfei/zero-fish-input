package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import java.io.File
import java.security.MessageDigest

/**
 * Developer-only evaluation of frozen public candidate-generation fixtures
 * against the bundled reference dictionary.  Shares the metrics vocabulary of
 * [AssociationQualityEvaluation]: coverage, top-1/3/8 and abstention.  The
 * dictionary is deterministic JVM data, so results are compared to the
 * recorded baseline exactly; ranking-policy metrics (first-choice gain,
 * clean regression) belong to suites that carry an actual policy toggle.
 */
internal object CandidateQualityEvaluation {
    data class Case(val id: String, val language: InputLanguage, val category: String,
        val reading: String, val expected: Set<String>)
    data class Result(val case: Case, val count: Int, val rank: Int, val acceptable: Int)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val dictionary = ReferenceDictionary.load()
        val bytes = checkNotNull(javaClass.getResourceAsStream("/candidate-quality.tsv")).use { it.readBytes() }
        val results = evaluate(dictionary, readCases(bytes.toString(Charsets.UTF_8)))
        val output = File(args[0]).apply { parentFile.mkdirs() }
        output.writeText(buildString {
            appendLine("# fixture_sha256=${sha256(bytes)}")
            val data = checkNotNull(CandidateQualityEvaluation::class.java.getResourceAsStream("/reference-pinyin.tsv"))
                .use { it.readBytes() }
            appendLine("# corpus_sha256=${sha256(data)}")
            appendLine("id\tlanguage\tcategory\tpositive\treturned\tfirst_acceptable_rank\tacceptable_returned")
            for (result in results) with(result) {
                appendLine("${case.id}\t${case.language.name}\t${case.category}\t${case.expected.isNotEmpty()}\t$count\t$rank\t$acceptable")
            }
        }, Charsets.UTF_8)
        val positive = results.filter { it.case.expected.isNotEmpty() }
        val negative = results.filter { it.case.expected.isEmpty() }
        println("positive=${positive.size}, covered=${positive.count { it.count > 0 }}, " +
            "top1=${positive.count { it.rank == 1 }}, top3=${positive.count { it.rank in 1..3 }}, " +
            "top8=${positive.count { it.rank in 1..8 }}, " +
            "abstention_false_positives=${negative.count { it.count > 0 }}/${negative.size}")
    }

    fun readCases(text: String): List<Case> {
        val cases = text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 5)
            require(fields[0].matches(Regex("[a-z]{2}[0-9]{3}")))
            require(fields[1] == "zh") { "Candidate fixtures cover the Chinese reference dictionary" }
            require(fields[3].length in 1..64 && fields[3].all { it in 'a'..'z' || it == '\'' })
            Case(fields[0], InputLanguage.CHINESE, fields[2], fields[3],
                if (fields[4] == "-") emptySet() else fields[4].split('|').toSet())
        }.toList()
        require(cases.size == cases.map { it.id }.distinct().size)
        return cases
    }

    fun evaluate(dictionary: ReferenceDictionary, cases: List<Case>): List<Result> = cases.map { case ->
        val words = dictionary.lookup(case.reading).take(8)
        val rank = words.indexOfFirst { it in case.expected }.let { if (it < 0) 0 else it + 1 }
        Result(case, words.size, rank, words.count { it in case.expected })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
