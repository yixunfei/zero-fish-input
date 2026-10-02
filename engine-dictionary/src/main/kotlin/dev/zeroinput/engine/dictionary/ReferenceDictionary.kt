package dev.zeroinput.engine.dictionary

import java.util.TreeMap

internal class ReferenceDictionary private constructor(private val prefixes: Map<String, List<String>>) {
    fun lookup(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val prefix = input.replace("'", "")
        if (prefix.isEmpty()) return emptyList()
        return prefixes[prefix].orEmpty()
    }

    companion object {
        fun load(): ReferenceDictionary {
            val stream = checkNotNull(ReferenceDictionary::class.java.getResourceAsStream("/reference-pinyin.tsv")) {
                "Reference dictionary is missing"
            }
            val entries = TreeMap<String, MutableList<String>>()
            var entryCount = 0
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    check(++entryCount <= MAX_ENTRIES) { "Reference dictionary exceeds $MAX_ENTRIES entries" }
                    val columns = line.split('\t')
                    check(columns.size == 2 && columns[0].length in 1..64 && columns[0].all { it in 'a'..'z' }) {
                        "Invalid reference dictionary entry"
                    }
                    check(columns[1].length in 1..64 && columns[1].none(Char::isISOControl)) {
                        "Invalid reference dictionary value"
                    }
                    entries.getOrPut(columns[0]) { mutableListOf() }.add(columns[1])
                }
            }
            check(entries.isNotEmpty()) { "Reference dictionary is empty" }
            // The bounded bundled reference fixture is indexed once on its factory worker.
            val prefixes = HashMap<String, LinkedHashSet<String>>()
            for ((reading, values) in entries) for (length in 1..reading.length) {
                prefixes.getOrPut(reading.take(length)) { LinkedHashSet() }.addAll(values)
            }
            return ReferenceDictionary(prefixes.mapValues { it.value.toList() })
        }

        private const val MAX_ENTRIES = 512
    }
}
