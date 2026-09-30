package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.GlideKey
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideLexiconEntry
import dev.zeroinput.engine.api.GlidePoint
import dev.zeroinput.engine.api.GlideRequest
import dev.zeroinput.engine.dictionary.DictionaryGlideDecoder
import dev.zeroinput.engine.english.EnglishGlideLexicon
import java.io.File
import kotlin.math.sin

/** Public deterministic synthetic trajectories through production lexicons; never a real-user accuracy claim. */
object GlideCorpusEvaluation {
    data class Case(val id: String, val layout: GlideLayout, val code: String)
    data class Result(val id: String, val layout: GlideLayout, val mode: String, val rank: Int, val micros: Long)
    data class Evaluation(val counts: Map<GlideLayout, Int>, val loadMillis: Long, val heapBytes: Long, val results: List<Result>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Supply repository root and report file" }
        val evaluation = evaluate(File(args[0]))
        writeReport(evaluation, File(args[1]))
        for ((layout, rows) in evaluation.results.groupBy { it.layout }) {
            val latency = rows.map { it.micros }.sorted()
            println("${layout.name}: n=${rows.size}, top1=${rows.count { it.rank == 1 }}, " +
                "top8=${rows.count { it.rank in 1..8 }}, p50_us=${latency[latency.size / 2]}, " +
                "p95_us=${latency[(latency.size * 0.95).toInt().coerceAtMost(latency.lastIndex)]}")
        }
        println("load_ms=${evaluation.loadMillis}; post_gc_observed_heap_bytes=${evaluation.heapBytes}")
    }

    fun writeReport(evaluation: Evaluation, report: File) {
        report.parentFile.mkdirs()
        report.writeText(buildString {
            appendLine("# Public synthetic traces; not real-user recognition accuracy.")
            appendLine("# load_ms=${evaluation.loadMillis}; post_gc_observed_heap_bytes=${evaluation.heapBytes}")
            evaluation.counts.forEach { (layout, count) -> appendLine("# ${layout.name}_codes=$count") }
            appendLine("id\tlayout\tmode\trank\tmicroseconds")
            for (row in evaluation.results) appendLine("${row.id}\t${row.layout}\t${row.mode}\t${row.rank}\t${row.micros}")
        })
    }

    fun evaluate(root: File): Evaluation {
        val start = System.nanoTime()
        val lexicon = EnglishGlideLexicon.loadBundled() + chineseLexicon(root)
        val decoder = DictionaryGlideDecoder(lexicon)
        val loadMillis = (System.nanoTime() - start) / 1_000_000
        val results = cases().flatMap { case -> listOf("center", "noise", "compact", "held_turn").map { mode ->
            val request = request(case, mode)
            val decodeStart = System.nanoTime()
            val codes = decoder.decode(request) { false }.map { it.inputCode }
            Result(case.id, case.layout, mode, codes.indexOf(case.code) + 1, (System.nanoTime() - decodeStart) / 1_000)
        } }
        val runtime = Runtime.getRuntime()
        // Developer evaluation only. Keep both the source rows and searchable index live for this estimate.
        runtime.gc()
        val heapBytes = runtime.totalMemory() - runtime.freeMemory()
        check(decoder.decode(request(cases().first(), "center")) { false }.isNotEmpty())
        return Evaluation(lexicon.groupingBy { it.layout }.eachCount(), loadMillis, heapBytes, results)
    }

    fun chineseLexicon(root: File): List<GlideLexiconEntry> =
        File(root, "engine-rime/src/main/assets/rime/luna_pinyin.dict.yaml").reader(Charsets.UTF_8).use { dictionary ->
            File(root, "engine-rime/src/main/assets/rime/essay.txt").reader(Charsets.UTF_8).use { frequencies ->
                RimeGlideLexicon.read(dictionary, frequencies)
            }
        }

    fun cases(): List<Case> {
        val english = ("hello privacy keyboard input offline world tomorrow meeting floating single android " +
            "message welcome thank information language handwriting beautiful accommodation committee " +
            "address book coffee afternoon security application system candidate development " +
            "photosynthesis astronomy rainforest library independent algorithm receive people").split(' ')
            .mapIndexed { index, word -> Case("en$index", GlideLayout.ENGLISH_QWERTY, word) }
        val chinese = listOf("ni hao", "ming tian", "xie xie", "zhong guo", "shu ru fa", "wo men", "jin tian",
            "gong zuo", "bei jing", "peng you", "shi jie", "jian pan", "xue xi", "sheng huo", "kuai le")
        return english + chinese.flatMapIndexed { index, reading ->
            val syllables = reading.split(' ')
            val full = syllables.joinToString("")
            listOf(
                Case("zh$index", GlideLayout.PINYIN_QWERTY, full),
                Case("ms$index", GlideLayout.DOUBLE_PINYIN_MICROSOFT,
                    syllables.joinToString("") { checkNotNull(DoublePinyin.encode(it, DoublePinyinScheme.MICROSOFT)) }),
                Case("zr$index", GlideLayout.DOUBLE_PINYIN_ZIRANMA,
                    syllables.joinToString("") { checkNotNull(DoublePinyin.encode(it, DoublePinyinScheme.ZIRANMA)) }),
                Case("nk$index", GlideLayout.PINYIN_NINE_KEY, full.map { NineKeyReadings.digitFor(it) }.joinToString("")),
            )
        }
    }

    private fun request(case: Case, mode: String): GlideRequest {
        var keys = keys(case.layout)
        if (mode == "compact") keys = keys.map { key ->
            GlideKey(key.code, key.left * 0.64f + 0.18f, key.top * 0.7f + 0.2f,
                key.right * 0.64f + 0.18f, key.bottom * 0.7f + 0.2f)
        }
        val points = ArrayList<GlidePoint>()
        val centers = case.code.filter { it != '\'' }.map { code ->
            val key = keys.first { it.code == code }
            (key.left + key.right) / 2 to (key.top + key.bottom) / 2
        }
        centers.zipWithNext().forEach { (first, second) ->
            for (step in 0 until 5) {
                val fraction = step / 5f
                val noise = if (mode == "noise") sin(fraction * Math.PI).toFloat() * 0.018f else 0f
                points += GlidePoint((first.first + (second.first - first.first) * fraction + noise).coerceIn(0f, 1f),
                    (first.second + (second.second - first.second) * fraction - noise).coerceIn(0f, 1f), points.size * 9L)
            }
            if (mode == "held_turn") repeat(5) {
                points += GlidePoint(second.first, second.second, points.size * 9L)
            }
        }
        val last = centers.last()
        points += GlidePoint(last.first, last.second, points.size * 9L)
        return GlideRequest(case.layout, points, keys)
    }

    private fun keys(layout: GlideLayout): List<GlideKey> {
        if (layout == GlideLayout.PINYIN_NINE_KEY) return ('2'..'9').map { code ->
            val index = code - '1'
            GlideKey(code, (index % 3) / 3f, (index / 3) / 3f, (index % 3 + 1) / 3f, (index / 3 + 1) / 3f)
        }
        return listOf("qwertyuiop", "asdfghjkl;", "zxcvbnm").flatMapIndexed { row, letters ->
            val offset = if (row == 2) 1.2f else 0f
            letters.mapIndexed { column, code ->
                GlideKey(code, (column + offset) / 10f, row / 3f, (column + offset + 1) / 10f, (row + 1) / 3f)
            }
        }
    }
}
