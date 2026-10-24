package dev.zeroinput.ime.personalization

import android.content.Context
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.engine.rime.BundledDictionaryReader

/** Worker-only public character readings. No personal words are cached or written here. */
internal class PersonalPhraseReadings(context: Context) {
    private val reader = BundledDictionaryReader(context.applicationContext)
    private val readings by lazy {
        val values = HashMap<String, MutableSet<String>>()
        reader.withInput("zi") { input ->
            RimeTextDictionaryParser().parse(input) { entry ->
                if (entry.text.codePointCount(0, entry.text.length) == 1 &&
                    entry.reading.isNotEmpty() && ' ' !in entry.reading) {
                    val options = values.getOrPut(entry.text) { linkedSetOf() }
                    if (options.size < 12) options.add(entry.reading)
                }
            }
        }
        values.mapValues { it.value.toList() }
    }

    fun matches(text: String, query: String): Boolean {
        if (query.isEmpty() || query.length > 64 || query.any { it !in 'a'..'z' }) return false
        return matchesReadings(text, query, readings)
    }

    companion object {
        /** Dynamic prefix matching supports polyphones without expanding reading combinations. */
        internal fun matchesReadings(text: String, query: String, readings: Map<String, List<String>>): Boolean {
            var positions = setOf(0)
            var index = 0
            while (index < text.length && positions.isNotEmpty()) {
                val next = index + Character.charCount(text.codePointAt(index))
                val options = readings[text.substring(index, next)].orEmpty()
                val following = HashSet<Int>()
                for (position in positions) for (reading in options) {
                    val remaining = query.substring(position)
                    if (reading.startsWith(remaining)) return true
                    if (remaining.startsWith(reading)) following += position + reading.length
                    if (remaining.firstOrNull() == reading.firstOrNull()) {
                        if (remaining.length == 1) return true
                        following += position + 1
                    }
                }
                positions = following
                index = next
            }
            return query.length in positions
        }
    }
}
