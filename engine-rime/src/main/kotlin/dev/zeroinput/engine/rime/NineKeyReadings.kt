package dev.zeroinput.engine.rime

import java.util.TreeMap

/** Uses public dictionary syllables; segmentation and candidate ranking stay in Rime. */
internal class NineKeyReadings(syllables: List<String>) {
    private val codes = TreeMap<String, List<String>>()

    init {
        require(syllables.size in 1..1024)
        require(syllables.all { word -> word.length in 1..8 && word.all { it in 'a'..'z' } })
        codes.putAll(syllables.distinct().groupBy(::digits))
    }

    fun choices(raw: String, comments: Array<String>): List<String> {
        val span = numericSpan(raw) ?: return emptyList()
        val code = raw.substring(span)
        val available = LinkedHashSet<String>()
        for (size in 1..minOf(code.length, 8)) codes[code.take(size)]?.let(available::addAll)
        for ((key, values) in codes.tailMap(code)) {
            if (!key.startsWith(code) || available.size >= MAX_CHOICES) break
            available.addAll(values)
        }
        val syllableIndex = raw.take(span.first).count { it == '\'' }
        val ranked = comments.mapNotNull { comment -> comment.split(' ', '\'').filter(String::isNotBlank).getOrNull(syllableIndex) }
            .filter { it in available }
        return (ranked + available).distinct().take(MAX_CHOICES)
    }

    fun replace(raw: String, reading: String): String? {
        if (raw.length > 128 || reading.length !in 1..8 || reading.any { it !in 'a'..'z' }) return null
        val span = numericSpan(raw) ?: return null
        val remaining = raw.substring(span)
        val encoded = digits(reading)
        if (reading !in codes[encoded].orEmpty()) return null
        if (!remaining.startsWith(encoded) && !encoded.startsWith(remaining)) return null
        val consumed = minOf(encoded.length, remaining.length)
        val replacement = raw.replaceRange(span.first, span.first + consumed, "$reading'")
        return replacement.takeIf { it.length <= 128 }
    }

    private fun numericSpan(raw: String): IntRange? {
        val start = raw.indexOfFirst { it in '2'..'9' }
        if (start < 0) return null
        val size = raw.drop(start).takeWhile { it in '2'..'9' }.length
        return start until start + size
    }

    private fun digits(value: String): String = value.map { digitMap[it - 'a'] }.joinToString("")

    companion object {
        private const val MAX_CHOICES = 32
        private const val digitMap = "22233344455566677778889999"
    }
}
