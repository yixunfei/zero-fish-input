package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.NextWordPredictor
import dev.zeroinput.engine.api.NextWordSuggestion
import java.io.Reader
import java.util.Locale

/** Public phrase pairs, ordered by editorial priority; construction belongs to the engine worker. */
class WordAssociationIndex private constructor(
    private val entries: Map<InputLanguage, Map<String, List<String>>>,
) : NextWordPredictor {
    override fun suggest(language: InputLanguage, context: CharSequence, limit: Int): List<NextWordSuggestion> {
        if (context.isEmpty() || context.length > 32 || limit <= 0) return emptyList()
        val value = context.toString().trimEnd(' ').lowercase(Locale.ROOT)
        val index = entries[language] ?: return emptyList()
        for (start in value.indices) {
            if (language == InputLanguage.ENGLISH && start > 0 && value[start - 1] != ' ') continue
            // Without Chinese word segmentation, a one-character suffix can be part of
            // an unrelated word (for example the final character of "忘我"). Keep
            // single-character anchors only for the complete context.
            if (language == InputLanguage.CHINESE && start > 0 && value.length - start == 1) continue
            val words = index[value.substring(start)] ?: continue
            val separator = if (language == InputLanguage.ENGLISH && context.last() != ' ') " " else ""
            return words.take(limit.coerceAtMost(8)).map { NextWordSuggestion(it, separator + it) }
        }
        return emptyList()
    }

    companion object {
        /** The bundled, project-authored seed vocabulary is independent of native Rime resources. */
        fun loadBundled(): WordAssociationIndex {
            val stream = checkNotNull(WordAssociationIndex::class.java.getResourceAsStream("/word-associations.tsv")) {
                "Missing association data"
            }
            return stream.bufferedReader(Charsets.UTF_8).use(::read)
        }

        internal fun read(reader: Reader): WordAssociationIndex {
            val entries = mutableMapOf<InputLanguage, MutableMap<String, MutableList<String>>>()
            reader.buffered().lineSequence().forEachIndexed { row, line ->
                require(row < 4096 && line.length <= 128) { "Invalid association data size" }
                if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
                val fields = line.split('\t')
                require(fields.size == 3) { "Invalid association row" }
                val language = when (fields[0]) {
                    "zh" -> InputLanguage.CHINESE
                    "en" -> InputLanguage.ENGLISH
                    else -> throw IllegalArgumentException("Unsupported association language")
                }
                val prefix = fields[1]
                val word = fields[2]
                require(prefix.length in 1..32 && word.length in 1..24 &&
                    prefix.all { it.isLetter() || it == ' ' } && word.all(Char::isLetter) &&
                    prefix == prefix.trim().lowercase(Locale.ROOT)) { "Invalid association text" }
                val words = entries.getOrPut(language) { mutableMapOf() }.getOrPut(prefix) { mutableListOf() }
                require(words.size < 8 && word !in words) { "Duplicate or excessive association" }
                words += word
            }
            require(entries.keys.containsAll(InputLanguage.entries)) { "Incomplete association data" }
            return WordAssociationIndex(entries.mapValues { (_, index) -> index.mapValues { it.value.toList() } })
        }
    }
}
