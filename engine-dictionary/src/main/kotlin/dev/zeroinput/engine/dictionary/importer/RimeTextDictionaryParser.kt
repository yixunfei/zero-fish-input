package dev.zeroinput.engine.dictionary.importer

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** Parses a bounded data dictionary, never Rime schemas, custom tags or executable configuration. */
class RimeTextDictionaryParser : PublicDictionaryParser {
    override fun parse(input: InputStream, emit: (PublicDictionaryEntry) -> Unit) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(input, decoder).buffered()
        val header = StringBuilder()
        var started = false
        var ended = false
        while (true) {
            val line = boundedLine(reader) ?: break
            if (line.trim() == "---") started = true
            if (line.trim() == "...") { ended = true; break }
            if (started) header.appendLine(line)
            require(header.length <= 32_768) { "Dictionary header too large" }
        }
        require(started && ended) { "Missing dictionary header" }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 8
            codePointLimit = 32_768
        }
        val metadata = Yaml(SafeConstructor(options)).load<Any>(header.toString()) as? Map<*, *>
            ?: error("Invalid dictionary header")
        val columns = (metadata["columns"] as? List<*>)?.map {
            require(it is String); it
        } ?: listOf("text", "code", "weight")
        require(columns.size in 1..4 && columns.toSet().size == columns.size && "text" in columns)
        require(columns.all { it in setOf("text", "code", "weight", "stem") })
        var count = 0
        while (true) {
            val line = boundedLine(reader) ?: break
            if (line.isBlank() || line.startsWith('#')) continue
            require(++count <= 5_000_000) { "Dictionary entry limit" }
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val fields = line.split('\t')
            require(fields.size <= columns.size)
            fun field(name: String) = columns.indexOf(name).takeIf { it >= 0 }?.let(fields::getOrNull).orEmpty()
            val weightText = field("weight")
            val weight = if (weightText.isEmpty()) 1L else {
                val number = weightText.removeSuffix("%").toDoubleOrNull() ?: error("Invalid dictionary weight")
                require(number.isFinite() && number >= 0 && number <= Int.MAX_VALUE)
                if (weightText.endsWith('%')) (number * 100).toLong() else number.toLong()
            }
            emit(PublicDictionaryEntry(field("text"), PublicPinyin.normalize(field("code")), weight))
        }
        require(count > 0) { "Empty dictionary" }
    }

    private fun boundedLine(reader: java.io.Reader): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return line.toString().takeIf { it.isNotEmpty() }
            if (value == 10) return line.toString().removeSuffix("\r")
            require(line.length < 8192) { "Dictionary line too long" }
            line.append(value.toChar())
        }
    }
}
