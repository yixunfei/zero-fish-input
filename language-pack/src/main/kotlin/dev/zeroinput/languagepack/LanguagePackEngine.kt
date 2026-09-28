package dev.zeroinput.languagepack

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputEngineFactory
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** A data-only engine for installed language packs. */
class LanguagePackEngineFactory(
    val pack: InstalledLanguagePack,
) : InputEngineFactory {
    private val entries by lazy { LanguagePackDictionaryLoader.load(pack) }
    private val baseLanguage = LanguagePackLanguage.fromTag(pack.manifest.languageTag)

    val packKey: String
        get() = pack.key

    override val descriptor = EngineDescriptor(
        id = pack.manifest.engineId,
        displayName = pack.manifest.displayName,
        version = pack.manifest.version,
        languages = setOfNotNull(baseLanguage),
    )

    override fun isAvailable(): Boolean = pack.enabled && baseLanguage != null && entries.isNotEmpty()

    override fun create(): InputEngine = LanguagePackInputEngine(descriptor, entries)
}

private class LanguagePackInputEngine(
    override val descriptor: EngineDescriptor,
    entries: List<PackDictionaryEntry>,
) : InputEngine {
    private val entries = entries.groupBy { it.shortcut }.mapValues { (_, values) ->
        values.map(PackDictionaryEntry::value).distinct()
    }
    private var input = ""
    private var currentSnapshot = EngineSnapshot.Empty
    private var candidatesAllowed = true

    override val snapshot: EngineSnapshot
        get() = currentSnapshot

    override fun start(context: EditorContext): EngineSnapshot {
        candidatesAllowed = !context.isSensitive &&
            (context.predictionsAllowed || context.language == InputLanguage.CHINESE)
        return reset()
    }

    override fun handle(key: EngineKey): EngineUpdate = when (key) {
        is EngineKey.Character -> handleCharacter(key.text)
        EngineKey.Backspace -> handleBackspace()
        EngineKey.Space -> commitBest(" ")
        EngineKey.Enter -> handleEnter()
    }

    override fun selectCandidate(index: Int): EngineUpdate {
        val candidate = currentSnapshot.candidates.getOrNull(index)
            ?: return EngineUpdate(currentSnapshot, consumed = false)
        input = ""
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = candidate.text)
    }

    override fun changePage(direction: PageDirection): EngineUpdate =
        EngineUpdate(currentSnapshot, consumed = false)

    override fun reset(): EngineSnapshot {
        input = ""
        currentSnapshot = EngineSnapshot.Empty
        return currentSnapshot
    }

    override fun close() {
        reset()
    }

    private fun handleCharacter(text: String): EngineUpdate {
        if (text.length != 1 || !isShortcutCharacter(text[0])) return commitBest(text)
        if (input.length >= MAX_INPUT) return EngineUpdate(currentSnapshot, consumed = false)
        input += text
        currentSnapshot = createSnapshot()
        return EngineUpdate(currentSnapshot)
    }

    private fun handleBackspace(): EngineUpdate {
        if (input.isEmpty()) return EngineUpdate(currentSnapshot, consumed = false)
        input = input.dropLast(1)
        currentSnapshot = createSnapshot()
        return EngineUpdate(currentSnapshot)
    }

    private fun handleEnter(): EngineUpdate {
        val candidate = currentSnapshot.candidates.firstOrNull()
            ?: return EngineUpdate(currentSnapshot, consumed = false)
        input = ""
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = candidate.text)
    }

    private fun commitBest(suffix: String): EngineUpdate {
        val value = currentSnapshot.candidates.firstOrNull()?.text ?: input
        input = ""
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = value + suffix)
    }

    private fun createSnapshot(): EngineSnapshot {
        if (input.isEmpty()) return EngineSnapshot.Empty
        if (!candidatesAllowed) return EngineSnapshot(rawInput = input, composition = input)
        val candidates = entries.asSequence()
            .filter { (shortcut, _) -> shortcut.startsWith(input, ignoreCase = true) }
            .flatMap { (shortcut, values) -> values.asSequence().map { shortcut to it } }
            .map { (_, value) -> value }
            .distinct()
            .take(MAX_CANDIDATES)
            .mapIndexed { index, value -> Candidate("pack:$index:$value", value) }
            .toList()
        return EngineSnapshot(input, input, candidates)
    }

    private fun isShortcutCharacter(value: Char): Boolean =
        value.isLetterOrDigit() || value == '\'' || value == '_' || value == '-'

    private companion object {
        const val MAX_CANDIDATES = 8
        const val MAX_INPUT = 128
    }
}

private data class PackDictionaryEntry(
    val shortcut: String,
    val value: String,
)

private object LanguagePackDictionaryLoader {
    private const val MAX_ENTRIES = 50_000
    private const val MAX_TEXT_FILE_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_TEXT_BYTES = 32L * 1024 * 1024

    fun load(pack: InstalledLanguagePack): List<PackDictionaryEntry> {
        val result = LinkedHashSet<PackDictionaryEntry>()
        var remainingBytes = MAX_TOTAL_TEXT_BYTES
        pack.manifest.files.forEach { declared ->
            val isJson = declared.path.substringAfterLast('.', "").equals("json", ignoreCase = true)
            if (declared.size > MAX_TEXT_FILE_BYTES || declared.size > remainingBytes) {
                if (isJson) return emptyList()
                return@forEach
            }
            if (result.size >= MAX_ENTRIES && !isJson) return@forEach
            val file = PackPathPolicy.resolveInside(pack.directory, declared.path)
            if (!file.isFile) {
                if (isJson) return emptyList()
                return@forEach
            }
            remainingBytes -= declared.size
            when (file.extension.lowercase()) {
                "json" -> if (!parseJson(file, result)) return emptyList()
                "txt", "yaml" -> parseLines(file, result)
            }
        }
        return result.toList()
    }

    private fun parseJson(file: File, output: MutableSet<PackDictionaryEntry>): Boolean =
        runCatching {
            val json = file.inputStream().use {
                LanguagePackManifestReader.read(it, MAX_TEXT_FILE_BYTES.toInt())
            }
            val value = LanguagePackJson.parse(json)
            require(value is JSONObject || value is JSONArray) { "Invalid language pack dictionary" }
            if (output.size >= MAX_ENTRIES) return@runCatching
            val parsed = LinkedHashSet<PackDictionaryEntry>()
            parseJsonValue(value, parsed)
            parsed.asSequence().take(MAX_ENTRIES - output.size).forEach(output::add)
        }.isSuccess

    private fun parseJsonValue(value: Any, output: MutableSet<PackDictionaryEntry>) {
        when (value) {
            is JSONObject -> {
                if (value.has("shortcut") && value.has("value")) {
                    add(value.optString("shortcut"), value.optString("value"), output)
                } else {
                    value.keys().asSequence().forEach { key ->
                        val child = value.opt(key) ?: return@forEach
                        when (child) {
                            is String -> {
                                when {
                                    looksLikeShortcut(key) -> add(key, child, output)
                                    looksLikeShortcut(child) -> add(child, key, output)
                                    else -> add(key, child, output)
                                }
                            }
                            is JSONArray -> child.asSequence().forEach { item ->
                                if (item is JSONObject) parseJsonValue(item, output)
                                else add(key, item.toString(), output)
                            }
                            else -> parseJsonValue(child, output)
                        }
                    }
                }
            }
            is JSONArray -> value.asSequence().forEach { item ->
                when (item) {
                    is JSONObject -> parseJsonValue(item, output)
                    is JSONArray -> {
                        val pair = item.asSequence().take(2).toList()
                        if (pair.size == 2) addPair(pair[0].toString(), pair[1].toString(), output)
                    }
                }
            }
        }
    }

    private fun parseLines(file: File, output: MutableSet<PackDictionaryEntry>) {
        runCatching {
            file.useLines(StandardCharsets.UTF_8) { lines ->
                lines.forEach { line ->
                    if (output.size >= MAX_ENTRIES) return@forEach
                    parseLine(line, output)
                }
            }
        }
    }

    private fun parseLine(raw: String, output: MutableSet<PackDictionaryEntry>) {
        val line = raw.trim().removePrefix("\uFEFF")
        if (line.isEmpty() || line.startsWith("#") || line == "---" || line == "...") return
        val fields = line.split('\t').map(String::trim).filter(String::isNotEmpty)
        if (fields.size >= 2) {
            val first = fields[0]
            val second = fields[1]
            addPair(first, second, output)
            return
        }
        val words = line.split(WHITESPACE).filter(String::isNotEmpty)
        if (words.size >= 2) {
            val first = words[0]
            val second = words[1]
            addPair(first, second, output)
            return
        }
        val separator = line.indexOf('=').takeIf { it > 0 } ?: line.indexOf(':').takeIf { it > 0 }
        if (separator != null) add(line.substring(0, separator), line.substring(separator + 1), output)
    }

    private fun looksLikeShortcut(value: String): Boolean =
        value.length <= 64 &&
            value.all { it.isLetterOrDigit() || it == '\'' || it == '_' || it == '-' } &&
            (value.any { it in 'a'..'z' || it in 'A'..'Z' } || value.all(Char::isDigit))

    private fun addPair(first: String, second: String, output: MutableSet<PackDictionaryEntry>) {
        when {
            looksLikeShortcut(first) -> add(first, second, output)
            looksLikeShortcut(second) -> add(second, first, output)
            else -> add(first, second, output)
        }
    }

    private fun add(shortcut: String, value: String, output: MutableSet<PackDictionaryEntry>) {
        if (output.size >= MAX_ENTRIES) return
        val cleanShortcut = shortcut.trim().lowercase()
        val cleanValue = value.trim()
        if (cleanShortcut.isEmpty() || cleanValue.isEmpty()) return
        if (cleanShortcut.length > 64 || cleanValue.length > 128) return
        if (cleanShortcut.any(Char::isISOControl) || cleanValue.any(Char::isISOControl)) return
        output += PackDictionaryEntry(cleanShortcut, cleanValue)
    }

    private fun JSONArray.asSequence(): Sequence<Any> = sequence {
        for (index in 0 until length()) {
            val item = opt(index)
            if (item != null) yield(item)
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
