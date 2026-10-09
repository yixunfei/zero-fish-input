package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideLexiconEntry
import java.io.BufferedReader
import java.io.Reader
import java.util.PriorityQueue
import java.util.concurrent.CancellationException

/** Reuses pinned public Rime data and the engine's existing double-pinyin mapping, never a user dictionary. */
object RimeGlideLexicon {
    /** A build-time bounded projection of verified Wanxiang readings; shared by every Chinese layout. */
    fun readPrepared(reader: Reader, isCancelled: () -> Boolean = { false }): List<GlideLexiconEntry> {
        val output = LinkedHashMap<Pair<GlideLayout, String>, GlideLexiconEntry>()
        forEachBoundedLine(reader, 24_000, isCancelled) { line ->
            val fields = line.split('\t')
            require(fields.size == 2)
            val syllables = fields[0].split(' ')
            require(syllables.size in 1..8 && syllables.all { s -> s.length in 1..8 && s.all { it in 'a'..'z' } })
            val weight = requireNotNull(fields[1].toIntOrNull())
            require(weight >= 0)
            addReading(output, Reading("", syllables), weight)
        }
        require(output.isNotEmpty())
        return output.values.toList()
    }
    private data class Reading(val text: String, val syllables: List<String>, val probability: Float = 1f)
    private data class Frequency(val text: String, val weight: Int)
    private data class Presets(val explicitWeights: Map<String, Int>, val words: List<Frequency>)

    /** Worker-only. Readers remain caller-owned. A cancelled or malformed load publishes no partial index. */
    fun read(
        dictionary: Reader,
        frequencies: Reader,
        isCancelled: () -> Boolean = { false },
    ): List<GlideLexiconEntry> {
        val readings = readDictionary(dictionary, isCancelled)
        require(readings.isNotEmpty()) { "Public glide readings are empty" }
        val wanted = readings.mapTo(HashSet()) { it.text }
        val presets = readFrequencies(frequencies, wanted, isCancelled)
        val output = LinkedHashMap<Pair<GlideLayout, String>, GlideLexiconEntry>()
        readings.forEachIndexed { index, reading ->
            if (index % CANCELLATION_INTERVAL == 0) checkCancelled(isCancelled)
            addReading(output, reading, presets.explicitWeights[reading.text] ?: 0)
        }
        val characterReadings = readings.filter { it.text.length == 1 && it.syllables.size == 1 }
            .groupBy { it.text[0] }.mapValues { (_, values) ->
                values.distinctBy { it.syllables }.sortedByDescending { it.probability }.take(MAX_POLYPHONE_VARIANTS)
            }
        presets.words.forEachIndexed { index, word ->
            if (index % CANCELLATION_INTERVAL == 0) checkCancelled(isCancelled)
            // Explicit word readings take precedence over inferred character combinations.
            if (word.text !in wanted) for (reading in expandWord(word.text, characterReadings)) {
                addReading(output, reading, word.weight)
            }
        }
        checkCancelled(isCancelled)
        return output.values.groupBy { it.layout }.values.flatMap { entries ->
            entries.sortedByDescending { it.frequency }.take(MAX_LAYOUT_CODES)
        }
    }

    private fun addReading(
        output: MutableMap<Pair<GlideLayout, String>, GlideLexiconEntry>,
        reading: Reading,
        baseFrequency: Int,
    ) {
        val frequency = (baseFrequency.toDouble() * reading.probability).toInt()
        val label = reading.syllables.joinToString(" ")
        for ((layout, code) in codes(reading.syllables)) {
            if (code.length !in 1..GlideLexiconEntry.MAX_CODE_LENGTH) continue
            val key = layout to code
            if (output[key]?.frequency?.let { it >= frequency } == true) continue
            output[key] = GlideLexiconEntry(layout, code, label, frequency)
        }
    }

    private fun expandWord(word: String, characterReadings: Map<Char, List<Reading>>): List<Reading> {
        var alternatives = listOf(Reading(word, emptyList()))
        for (character in word) {
            val readings = characterReadings[character] ?: return emptyList()
            alternatives = alternatives.flatMap { prefix -> readings.map { next ->
                Reading(word, prefix.syllables + next.syllables, prefix.probability * next.probability)
            } }.sortedByDescending { it.probability }.take(MAX_POLYPHONE_VARIANTS)
        }
        return alternatives
    }

    private fun readDictionary(reader: Reader, isCancelled: () -> Boolean): List<Reading> {
        val output = ArrayList<Reading>()
        var inData = false
        forEachBoundedLine(reader, MAX_DICTIONARY_LINES, isCancelled) { line ->
            if (line == "...") inData = true
            else if (inData && line.isNotBlank() && !line.startsWith('#')) {
                val columns = line.split('\t', limit = 4)
                require(columns.size >= 2) { "Malformed public glide dictionary" }
                val text = columns[0]
                val syllables = columns[1].split(' ')
                if (text.length in 1..32 && text.none(Char::isISOControl) && syllables.size in 1..8 &&
                    syllables.all { it.length in 1..8 && it.all { char -> char in 'a'..'z' } }) {
                    val proportion = columns.getOrNull(2)?.takeIf { it.endsWith('%') }
                        ?.removeSuffix("%")?.toFloatOrNull()?.div(100f) ?: 1f
                    require(proportion.isFinite() && proportion in 0f..1f) { "Invalid public reading weight" }
                    output += Reading(text, syllables, proportion)
                }
            }
        }
        require(inData) { "Missing public glide dictionary section" }
        return output
    }

    private fun readFrequencies(reader: Reader, wanted: Set<String>, isCancelled: () -> Boolean): Presets {
        val weights = HashMap<String, Int>()
        val commonWords = PriorityQueue<Frequency>(compareBy { it.weight })
        forEachBoundedLine(reader, MAX_FREQUENCY_LINES, isCancelled) { line ->
            if (line.isNotBlank() && !line.startsWith('#')) {
                val columns = line.split('\t')
                require(columns.size == 2) { "Malformed public glide frequency row" }
                val weight = columns[1].toLongOrNull()
                require(weight != null && weight >= 0) { "Invalid public glide frequency" }
                val frequency = weight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val text = columns[0]
                if (text in wanted) weights[text] = frequency
                else if (frequency > 0 && text.length in 2..8 && text.all { it in '\u3400'..'\u9fff' }) {
                    if (commonWords.size < MAX_PRESET_WORDS) commonWords.add(Frequency(text, frequency))
                    else if (frequency > commonWords.peek().weight) {
                        commonWords.remove()
                        commonWords.add(Frequency(text, frequency))
                    }
                }
            }
        }
        return Presets(weights, commonWords.toList())
    }

    private fun codes(syllables: List<String>): List<Pair<GlideLayout, String>> {
        val full = syllables.joinToString("")
        val output = mutableListOf(
            GlideLayout.PINYIN_QWERTY to full,
            GlideLayout.PINYIN_NINE_KEY to full.map { NineKeyReadings.digitFor(it) }.joinToString(""),
        )
        for ((layout, scheme) in listOf(
            GlideLayout.DOUBLE_PINYIN_MICROSOFT to DoublePinyinScheme.MICROSOFT,
            GlideLayout.DOUBLE_PINYIN_ZIRANMA to DoublePinyinScheme.ZIRANMA,
        )) {
            val encoded = syllables.map { DoublePinyin.encode(it, scheme) }
            if (encoded.none { it == null }) output += layout to encoded.joinToString("")
        }
        return output
    }

    private fun forEachBoundedLine(
        reader: Reader,
        maxLines: Int,
        isCancelled: () -> Boolean,
        consume: (String) -> Unit,
    ) {
        val buffered = if (reader is BufferedReader) reader else reader.buffered()
        val line = StringBuilder()
        var characters = 0
        var lines = 0
        while (true) {
            if (characters % 4096 == 0) checkCancelled(isCancelled)
            val next = buffered.read()
            if (next == -1) break
            require(++characters <= MAX_SOURCE_CHARACTERS) { "Public glide source exceeds bound" }
            when (val character = next.toChar()) {
                '\n' -> {
                    require(++lines <= maxLines) { "Public glide source has too many rows" }
                    consume(line.toString().removeSuffix("\r"))
                    line.setLength(0)
                }
                else -> {
                    require(line.length < MAX_LINE_LENGTH) { "Public glide source row exceeds bound" }
                    line.append(character)
                }
            }
        }
        if (line.isNotEmpty()) {
            require(++lines <= maxLines) { "Public glide source has too many rows" }
            consume(line.toString().removeSuffix("\r"))
        }
    }

    private fun checkCancelled(check: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || check()) throw CancellationException("Glide lexicon cancelled")
    }

    private const val MAX_SOURCE_CHARACTERS = 12_000_000
    private const val MAX_DICTIONARY_LINES = 100_000
    private const val MAX_FREQUENCY_LINES = 500_000
    private const val MAX_LINE_LENGTH = 1024
    private const val CANCELLATION_INTERVAL = 32
    private const val MAX_PRESET_WORDS = 24_000
    private const val MAX_POLYPHONE_VARIANTS = 3
    private const val MAX_LAYOUT_CODES = 75_000
}
