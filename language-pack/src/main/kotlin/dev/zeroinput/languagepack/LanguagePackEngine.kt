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
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A data-only engine for installed language packs. */
class LanguagePackEngineFactory(
    val pack: InstalledLanguagePack,
) : InputEngineFactory {
    private val dictionary by lazy {
        PackPrefixCandidates(LanguagePackDictionaryLoader.load(pack).map { it.shortcut to it.value })
    }
    private val baseLanguage = LanguagePackLanguage.fromTag(pack.manifest.languageTag)

    val packKey: String
        get() = pack.key

    override val descriptor = EngineDescriptor(
        id = pack.manifest.engineId,
        displayName = pack.manifest.displayName,
        version = pack.manifest.version,
        languages = setOfNotNull(baseLanguage),
    )

    override fun isAvailable(): Boolean = pack.enabled && baseLanguage != null && !dictionary.isEmpty

    override fun create(): InputEngine = LanguagePackInputEngine(descriptor, dictionary)
}

private class LanguagePackInputEngine(
    override val descriptor: EngineDescriptor,
    private val dictionary: PackPrefixCandidates,
) : InputEngine {
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
        if (input.isEmpty()) return EngineUpdate(currentSnapshot, consumed = false)
        val raw = input
        input = ""
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = raw)
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
        val candidates = dictionary.find(input.lowercase())
            .mapIndexed { index, value -> Candidate("pack:$index:$value", value) }
            .toList()
        return EngineSnapshot(input, input, candidates)
    }

    private fun isShortcutCharacter(value: Char): Boolean =
        value.isLetterOrDigit() || value == '\'' || value == '_' || value == '-'

    private companion object {
        const val MAX_INPUT = 128
    }
}

private object LanguagePackDictionaryLoader {
    private const val MAX_ENTRIES = 50_000
    private const val MAX_LINE_CHARS = 4096
    private const val MAX_LINE_FIELDS = 16
    private const val MAX_TEXT_FILE_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_TEXT_BYTES = 32L * 1024 * 1024

    fun load(pack: InstalledLanguagePack): List<PackDictionaryEntry> {
        val result = LinkedHashSet<PackDictionaryEntry>()
        var remainingBytes = MAX_TOTAL_TEXT_BYTES
        pack.manifest.files.forEach { declared ->
            if (declared.size !in 1..MAX_TEXT_FILE_BYTES || declared.size > remainingBytes) return emptyList()
            val file = recoverable { PackPathPolicy.resolveInside(pack.directory, declared.path) }.getOrNull()
                ?: return emptyList()
            if (!readVerified(file, declared, result)) return emptyList()
            remainingBytes -= declared.size
        }
        return result.toList()
    }

    /** Verify the bytes actually parsed, including files beyond the candidate budget. */
    private fun readVerified(file: File, declared: LanguagePackFile,
        result: MutableSet<PackDictionaryEntry>): Boolean = recoverable {
        require(file.isFile && file.length() == declared.size)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { source ->
            val bounded = object : java.io.FilterInputStream(source) {
                override fun read(): Int = `in`.read().also { if (it >= 0) { total++; require(total <= declared.size) } }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    `in`.read(bytes, offset, minOf(length.toLong(), declared.size - total + 1).toInt()).also {
                        if (it > 0) { total += it; require(total <= declared.size) }
                    }
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            java.io.InputStreamReader(java.security.DigestInputStream(bounded, digest), decoder).buffered().use { reader ->
                when (file.extension.lowercase()) {
                    "json" -> result.addAll(LanguagePackDictionaryJson.read(reader, MAX_ENTRIES - result.size))
                    "txt", "yaml" -> readLines(reader, result)
                    else -> error("Unsupported dictionary format")
                }
            }
        }
        require(total == declared.size)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(hash == declared.sha256)
        true
    }.getOrDefault(false)

    /** Validate every line, including data beyond the output budget, without allocating an unbounded line. */
    private fun readLines(reader: java.io.Reader, result: MutableSet<PackDictionaryEntry>) {
        val line = StringBuilder()
        fun accept() {
            val text = line.toString()
            require(isBoundedLine(text))
            if (result.size < MAX_ENTRIES) parseLine(text, result)
            line.setLength(0)
        }
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val value = reader.read()
            if (value < 0) break
            val character = value.toChar()
            if (character == '\n' || character == '\r') accept() else {
                require(line.length < MAX_LINE_CHARS)
                line.append(character)
            }
        }
        accept()
    }

    private fun isBoundedLine(raw: String): Boolean {
        if (raw.length > MAX_LINE_CHARS) return false
        var fields = 0
        var inField = false
        for (character in raw) {
            if (character.isWhitespace()) inField = false
            else if (!inField) {
                if (++fields > MAX_LINE_FIELDS) return false
                inField = true
            }
        }
        return raw.count { it == '\t' } < MAX_LINE_FIELDS
    }

    private fun parseLine(raw: String, output: MutableSet<PackDictionaryEntry>) {
        if (!isBoundedLine(raw)) return
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

    private fun addPair(first: String, second: String, output: MutableSet<PackDictionaryEntry>) {
        if (output.size < MAX_ENTRIES) PackDictionaryEntry.pair(first, second)?.let(output::add)
    }

    private fun add(shortcut: String, value: String, output: MutableSet<PackDictionaryEntry>) {
        if (output.size < MAX_ENTRIES) PackDictionaryEntry.create(shortcut, value)?.let(output::add)
    }

    private val WHITESPACE = Regex("\\s+")
}
