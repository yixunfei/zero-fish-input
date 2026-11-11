package dev.zeroinput.ime.input

import java.io.BufferedReader
import java.text.Normalizer
import java.util.TreeMap

internal class HanShapeIndex(reader: BufferedReader) {
    data class Match(val character: String, val pinyin: String)

    private data class Entry(
        val match: Match,
        val frequency: Int,
        val structure: String,
        val direct: List<Int>,
        val nested: List<Int>,
    )

    private val entries = HashMap<Int, Entry>()
    private val components = HashMap<Int, MutableList<Int>>()
    private val pinyin = TreeMap<String, MutableList<Int>>()
    private val hanOrder = compareByDescending<Int> { entries.getValue(it).frequency }
        .thenBy { it !in 0x4e00..0x9fff }.thenBy { it }

    init {
        reader.forEachLine { line ->
            val fields = line.split('\t', limit = 6)
            check(fields.size == 6)
            val (character, reading, frequency, structure, direct) = fields
            val key = character.codePointAt(0)
            val entry = Entry(Match(character, reading.replace(",", " · ")), frequency.toInt(),
                structure, parts(direct), parts(fields[5]))
            check(entries.put(key, entry) == null)
            (entry.direct + entry.nested).distinct().forEach {
                components.getOrPut(it, ::ArrayList).add(key)
            }
            reading.split(',').map(::normalizeReading).distinct().forEach {
                pinyin.getOrPut(it, ::ArrayList).add(key)
            }
        }
    }

    fun seeds(prefix: String): List<Match> {
        if (prefix.isEmpty() || prefix.length > 8 || prefix.any { it !in 'a'..'z' }) return emptyList()
        return pinyin.tailMap(prefix).asSequence()
            .takeWhile { (reading, _) -> reading.startsWith(prefix) }
            .flatMap { (_, keys) -> keys.asSequence().sortedWith(hanOrder) }
            .distinct().map { entries.getValue(it).match }.toList()
    }

    fun similar(seed: String, radical: Boolean): List<Match> {
        if (seed.codePointCount(0, seed.length) != 1) return emptyList()
        val key = seed.codePointAt(0)
        val entry = entries[key].takeUnless { radical }
        val component = canonical(key)
        val shared = entry?.direct.orEmpty().filter { it != component }.distinct()
        val keys = (listOf(component) + shared).asSequence()
            .flatMap { components[it].orEmpty().asSequence() }.distinct().filter { it != key }
        return keys.map { candidate ->
            val result = entries.getValue(candidate)
            val score = when (component) {
                in result.direct -> 1_000
                in result.nested -> 700
                else -> 0
            } + shared.sumOf { part ->
                val rarity = 200 / (1 + components[part].orEmpty().size / 40)
                when (part) {
                    in result.direct -> rarity + 40
                    in result.nested -> rarity / 3 + 10
                    else -> 0
                }
            } + if (entry != null && entry.structure == result.structure) 20 else 0
            candidate to score
        }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }
            .thenComparator { left, right -> hanOrder.compare(left.first, right.first) })
            .map { (candidate, _) -> entries.getValue(candidate).match }.toList()
    }

    private fun parts(text: String): List<Int> =
        if (text == "-") emptyList() else text.codePoints().toArray().map(::canonical).distinct()

    private companion object {
        val TONED_UMLAUT = Regex("[ǖǘǚǜ]")
        val VARIANTS = listOf("人亻", "刀刂", "心忄㣺", "手扌龵", "水氵氺", "火灬",
            "犬犭", "示礻", "衣衤", "艸艹䒑", "竹⺮", "网罒罓", "言讠",
            "金钅", "食飠饣", "糸糹纟", "貝贝", "門门", "辵辶", "足𧾷", "見见",
            "車车", "馬马", "魚鱼", "鳥鸟", "頁页", "風风", "飛飞", "齒齿")
            .flatMap { family -> family.codePoints().toArray().map { it to family.codePointAt(0) } }.toMap()

        fun canonical(value: Int): Int = VARIANTS[value] ?: value

        fun normalizeReading(value: String): String = Normalizer.normalize(
            value.replace('ü', 'v').replace(TONED_UMLAUT, "v"), Normalizer.Form.NFD,
        ).filter { it in 'a'..'z' }
    }
}
