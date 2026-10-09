package dev.zeroinput.engine.dictionary.importer

import java.text.Normalizer

/** Public source data only. Empty readings are resolved by Rime's public character table. */
data class PublicDictionaryEntry(val text: String, val reading: String, val weight: Long) {
    init {
        require(text.length in 1..256 && text.none { it.isISOControl() || it == '\uFFFD' })
        require(reading.length <= 1024 && (reading.isEmpty() ||
            reading.split(' ').all { part -> part.length in 1..12 && part.all { it in 'a'..'z' } }))
        require(weight in 0..Int.MAX_VALUE.toLong())
    }
}

object PublicPinyin {
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
            .replace("u\u0308", "v").replace("u:", "v")
        return decomposed.filterNot {
            Character.getType(it) == Character.NON_SPACING_MARK.toInt() || it in '1'..'5'
        }.trim().split(Regex("\\s+")).joinToString(" ")
    }
}

fun interface PublicDictionaryParser {
    /** Streaming, worker-only; throws on malformed data and never publishes a partial installation. */
    fun parse(input: java.io.InputStream, emit: (PublicDictionaryEntry) -> Unit)
}
