package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.LearnedSuggestionSource
import dev.zeroinput.engine.api.PersonalFrequencyStore
import dev.zeroinput.engine.api.PersonalFrequencyLookup
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.engine.api.PagedPersonalizationStore
import dev.zeroinput.engine.api.PersonalSuggestionPage
import dev.zeroinput.engine.api.WeightedTerm
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import dev.zeroinput.userdata.UserLexiconFormat.MAX_FREQUENCY
import dev.zeroinput.userdata.UserLexiconFormat.MAX_SHORTCUT_LENGTH
import dev.zeroinput.userdata.UserLexiconFormat.MAX_TERMS
import dev.zeroinput.userdata.UserLexiconFormat.MAX_VALUE_LENGTH
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

/** All methods that access the store must run on a background worker. */
class UserLexiconRepository(
    private val store: EncryptedStore,
    private val exportCipher: EncryptedExportCipher = KeyStoreExportCipher(SecurityAliases.USER_DATA_EXPORT),
    private val readingMatches: (String, String) -> Boolean = { _, _ -> false },
) : LearnedSuggestionSource, PagedPersonalizationStore, PersonalFrequencyStore {
    constructor(context: Context, readingMatches: (String, String) -> Boolean = { _, _ -> false }) : this(EncryptedFileStore(
        context = context,
        fileName = "user-lexicon.bin",
        keyAlias = SecurityAliases.USER_DATA,
    ), KeyStoreExportCipher(SecurityAliases.USER_DATA_EXPORT), readingMatches)

    private val lock = Any()
    private val generation = AtomicLong(0L)
    private var terms: List<UserTerm>? = null
    private var prefixIndex: Map<IndexKey, List<UserTerm>> = emptyMap()
    private var frequencyLookup: PersonalFrequencyLookup? = null
    private var listIndex: Map<InputLanguage, List<UserTerm>> = emptyMap()
    private var allListIndex: List<UserTerm> = emptyList()
    private var deletionPending = false
    private val writeFailureListeners = CopyOnWriteArrayList<(Throwable) -> Unit>()
    @Volatile private var writeFailure: UserDictionaryFailure? = null

    fun warmUp() {
        synchronized(lock) { loadTerms() }
    }

    override fun suggestions(prefix: String, limit: Int): List<WeightedTerm> =
        suggestionsFor(prefix, InputLanguage.ENGLISH, limit).map { WeightedTerm(it.text, it.frequency) }

    override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int): List<PersonalSuggestion> =
        suggestionPage(prefix, language, 0, limit).items

    override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int): PersonalSuggestionPage =
        synchronized(lock) {
            require(limit in 0..MAX_SUGGESTION_LIMIT)
            require(offset in 0..MAX_TERMS)
            loadTerms()
            val normalized = prefix.trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty() || limit == 0) return@synchronized PersonalSuggestionPage()
            val indexed = prefixIndex[IndexKey(language, normalized)].orEmpty()
            val exact = indexed.asSequence().filter { it.shortcut.equals(normalized, ignoreCase = true) }
            val remainder = indexed.asSequence().filterNot { it.shortcut.equals(normalized, ignoreCase = true) }
            val values = exact.plus(remainder)
                .drop(offset)
                .take(limit + 1)
                .map { PersonalSuggestion(it.id, it.value, it.frequency, it.shortcut) }
                .toList()
            PersonalSuggestionPage(values.take(limit), values.size > limit)
        }

    /**
     * Exact-value frequency lookup for association reranking.  Like every
     * other store access here it must run on a background worker; the IME
     * input thread reaches this data only through the queued adapter's
     * prepared cache.
     */
    override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> =
        frequencyLookupSnapshot().frequenciesFor(words, language)

    override fun frequencyLookupSnapshot(): PersonalFrequencyLookup = synchronized(lock) {
        loadTerms()
        checkNotNull(frequencyLookup)
    }

    fun list(language: InputLanguage? = null): List<UserTerm> = synchronized(lock) {
        loadTerms()
        if (language != null) listIndex[language].orEmpty() else allListIndex
    }

    fun addPhrase(
        shortcut: String,
        value: String,
        language: InputLanguage,
        frequency: Int = 1,
    ): UserTerm {
        val expected = generation.get()
        val cleanShortcut = UserLexiconFormat.validateShortcut(shortcut)
        val cleanValue = UserLexiconFormat.validateValue(value)
        require(frequency in 1..MAX_FREQUENCY)
        return synchronized(lock) {
            ensureCurrent(expected)
            val current = loadTerms().toMutableList()
            val index = findPhrase(current, cleanShortcut, cleanValue, language)
            if (index < 0 && current.size >= MAX_TERMS) capacityExceeded()
            val now = System.currentTimeMillis()
            val term = if (index >= 0) current[index].copy(lastUsedEpochMillis = now) else {
                UserTerm(UUID.randomUUID().toString(), cleanShortcut, cleanValue, language, frequency, now)
            }
            val updated = if (index >= 0) term.copy(frequency = frequency.coerceAtLeast(term.frequency)) else term
            if (index >= 0) current[index] = updated else current += updated
            persist(current, expected)
            updated
        }
    }

    /** Management queries run on a worker and expose at most one bounded page. */
    fun search(shortcutPrefix: String, language: InputLanguage? = null, limit: Int = 50): List<UserTerm> =
        searchPage(shortcutPrefix, language, 0, limit).items

    fun searchPage(query: String, language: InputLanguage? = null, offset: Int = 0, limit: Int = 50): UserTermPage {
        require(limit in 1..MAX_SUGGESTION_LIMIT && offset in 0..MAX_TERMS)
        require(query.length <= MAX_SHORTCUT_LENGTH)
        val normalized = query.trim().lowercase(Locale.ROOT).filterNot { it == ' ' || it == '\'' }
        return synchronized(lock) {
            loadTerms()
            val source = if (language == null) allListIndex else listIndex[language].orEmpty()
            val page = source.asSequence().filter { term ->
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                normalized.isEmpty() || term.shortcut.lowercase(Locale.ROOT).startsWith(normalized) ||
                    term.value.startsWith(normalized) ||
                    term.language == InputLanguage.CHINESE && readingMatches(term.value, normalized)
            }.drop(offset).take(limit + 1).toList()
            UserTermPage(page.take(limit), page.size > limit)
        }
    }
    fun updatePhrase(
        id: String,
        shortcut: String,
        value: String,
        language: InputLanguage,
        frequency: Int,
    ): UserTerm {
        val expected = generation.get()
        val cleanShortcut = UserLexiconFormat.validateShortcut(shortcut)
        val cleanValue = UserLexiconFormat.validateValue(value)
        require(frequency in 1..MAX_FREQUENCY)
        return synchronized(lock) {
            ensureCurrent(expected)
            val current = loadTerms().toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) throw UserDictionaryException(UserDictionaryFailure.INVALID_FORMAT)
            val duplicate = current.any {
                it.id != id && it.shortcut.equals(cleanShortcut, ignoreCase = true) &&
                    it.value == cleanValue && it.language == language
            }
            if (duplicate) throw UserDictionaryException(UserDictionaryFailure.INVALID_FORMAT)
            val updated = current[index].copy(
                shortcut = cleanShortcut,
                value = cleanValue,
                language = language,
                frequency = frequency,
                lastUsedEpochMillis = System.currentTimeMillis(),
            )
            current[index] = updated
            persist(current, expected)
            updated
        }
    }

    override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) {
        if (!learningAllowed) return
        val expected = generation.get()
        val cleanShortcut = shortcut.trim().lowercase(Locale.ROOT)
        val cleanValue = value.trim()
        val minimumValueLength = if (language == InputLanguage.CHINESE) 1 else 2
        if (!isLearnable(cleanShortcut, 1, MAX_SHORTCUT_LENGTH) ||
            !isLearnable(cleanValue, minimumValueLength, MAX_VALUE_LENGTH)) return
        synchronized(lock) {
            if (expected != generation.get()) return
            val current = loadTerms().toMutableList()
            val index = findPhrase(current, cleanShortcut, cleanValue, language)
            val now = System.currentTimeMillis()
            if (index >= 0) {
                val old = current[index]
                current[index] = old.copy(frequency = (old.frequency + 1).coerceAtMost(MAX_FREQUENCY), lastUsedEpochMillis = now)
            } else {
                current += UserTerm(UUID.randomUUID().toString(), cleanShortcut, cleanValue, language, 1, now)
            }
            if (current.size > MAX_TERMS) {
                current.sortWith(compareByDescending<UserTerm> { it.frequency }.thenByDescending { it.lastUsedEpochMillis })
                current.subList(MAX_TERMS, current.size).clear()
            }
            persist(current, expected)
        }
    }

    override fun recordUse(id: String, learningAllowed: Boolean) {
        if (!learningAllowed) return
        val expected = generation.get()
        synchronized(lock) {
            if (expected != generation.get()) return
            val current = loadTerms().toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val old = current[index]
            current[index] = old.copy(
                frequency = (old.frequency + 1).coerceAtMost(MAX_FREQUENCY),
                lastUsedEpochMillis = System.currentTimeMillis(),
            )
            persist(current, expected)
        }
    }

    fun remove(id: String): Boolean {
        val expected = generation.get()
        return synchronized(lock) {
            ensureCurrent(expected)
            val current = loadTerms().toMutableList()
            val removed = current.removeAll { it.id == id }
            if (removed) persist(current, expected)
            removed
        }
    }

    override fun clear() {
        // Advance before waiting for an in-flight write so earlier waiting updates cannot revive data.
        generation.incrementAndGet()
        synchronized(lock) {
            publishTerms(emptyList())
            deletionPending = true
            var failure: Throwable? = null
            try {
                store.delete(deleteKey = true)
            } catch (error: Throwable) {
                failure = error
            }
            try {
                if (exportCipher.hasKey()) exportCipher.deleteKey()
            } catch (error: Throwable) {
                failure = failure?.also { it.addSuppressed(error) } ?: error
            }
            if (failure != null) {
                deletionPending = true
                throw failure
            }
            deletionPending = false
        }
    }

    /**
     * Test-only plaintext export; callers own and must wipe the returned bytes.
     * The JSON library's intermediate String remains subject to garbage collection.
     */
    fun exportJson(): ByteArray = synchronized(lock) {
        UserLexiconFormat.serialize(loadTerms()).toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Returns an application-KeyStore encrypted export; plaintext never leaves this repository. */
    fun exportEncrypted(): ByteArray = synchronized(lock) {
        val plaintext = UserLexiconFormat.serialize(loadTerms()).toString().toByteArray(StandardCharsets.UTF_8)
        try {
            exportCipher.encrypt(plaintext, EXPORT_ASSOCIATED_DATA)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Imports only payloads produced by [exportEncrypted]. */
    fun importEncrypted(bytes: ByteArray, replace: Boolean): Int {
        if (bytes.size > UserLexiconFormat.MAX_IMPORT_BYTES) {
            throw UserDictionaryException(UserDictionaryFailure.IMPORT_TOO_LARGE)
        }
        val expected = generation.get()
        val plaintext = try {
            exportCipher.decrypt(bytes, EXPORT_ASSOCIATED_DATA)
        } catch (_: Exception) {
            throw UserDictionaryException(UserDictionaryFailure.INVALID_FORMAT)
        }
        return try {
            val imported = UserLexiconFormat.parse(plaintext)
            synchronized(lock) {
                ensureCurrent(expected)
                val target = if (replace) linkedMapOf() else loadTerms().associateByTo(linkedMapOf(), UserLexiconFormat::identity)
                for (candidate in imported) {
                    val identity = UserLexiconFormat.identity(candidate)
                    val existing = target[identity]
                    if (existing == null && target.size >= MAX_TERMS) capacityExceeded()
                    target[identity] = candidate.copy(id = existing?.id ?: UUID.randomUUID().toString())
                }
                persist(target.values.toList(), expected)
            }
            imported.size
        } finally {
            plaintext.fill(0)
        }
    }

    fun lastWriteFailure(): UserDictionaryFailure? = writeFailure

    fun addWriteFailureListener(listener: (UserDictionaryFailure) -> Unit): AutoCloseable {
        val wrapper: (Throwable) -> Unit = {
            val failure = writeFailure ?: UserDictionaryFailure.WRITE_FAILED
            listener(failure)
        }
        writeFailureListeners += wrapper
        return AutoCloseable { writeFailureListeners -= wrapper }
    }

    fun importJson(bytes: ByteArray, replace: Boolean): Int {
        val expected = generation.get()
        if (bytes.size > UserLexiconFormat.MAX_IMPORT_BYTES) {
            throw UserDictionaryException(UserDictionaryFailure.IMPORT_TOO_LARGE)
        }
        val imported = UserLexiconFormat.parse(bytes)
        synchronized(lock) {
            ensureCurrent(expected)
            val target = if (replace) linkedMapOf() else loadTerms().associateByTo(linkedMapOf(), UserLexiconFormat::identity)
            for (candidate in imported) {
                val identity = UserLexiconFormat.identity(candidate)
                val existing = target[identity]
                if (existing == null && target.size >= MAX_TERMS) capacityExceeded()
                // Imported identifiers never alias a different local phrase or candidate route.
                target[identity] = candidate.copy(id = existing?.id ?: UUID.randomUUID().toString())
            }
            persist(target.values.toList(), expected)
        }
        return imported.size
    }

    private fun loadTerms(): List<UserTerm> {
        check(!deletionPending) { "User dictionary deletion is incomplete" }
        terms?.let { return it }
        val bytes = store.read() ?: return emptyList<UserTerm>().also(::publishTerms)
        return try {
            UserLexiconFormat.parse(bytes).also(::publishTerms)
        } finally {
            bytes.fill(0)
        }
    }

    private fun persist(values: List<UserTerm>, expected: Long) {
        check(!deletionPending) { "User dictionary deletion is incomplete" }
        ensureCurrent(expected)
        val bytes = UserLexiconFormat.serialize(values).toString().toByteArray(StandardCharsets.UTF_8)
        try {
            if (bytes.size > UserLexiconFormat.MAX_IMPORT_BYTES) capacityExceeded()
            ensureCurrent(expected)
            try {
                store.write(bytes)
            } catch (error: Throwable) {
                writeFailure = UserDictionaryFailure.WRITE_FAILED
                writeFailureListeners.forEach { listener -> runCatching { listener(error) } }
                throw error
            }
            writeFailure = null
        } finally {
            bytes.fill(0)
        }
        if (expected == generation.get()) publishTerms(values) else publishTerms(emptyList())
    }

    private fun ensureCurrent(expected: Long) {
        check(expected == generation.get()) { "User dictionary operation was cancelled" }
    }

    private fun findPhrase(terms: List<UserTerm>, shortcut: String, value: String, language: InputLanguage): Int =
        terms.indexOfFirst { it.shortcut.equals(shortcut, ignoreCase = true) && it.value == value && it.language == language }

    private fun isLearnable(value: String, minimum: Int, maximum: Int): Boolean =
        value.length in minimum..maximum && value.none(Char::isISOControl) && value.none(Char::isWhitespace) &&
            StandardCharsets.UTF_8.newEncoder().canEncode(value)

    private fun capacityExceeded(): Nothing = throw UserDictionaryException(UserDictionaryFailure.CAPACITY_EXCEEDED)

    private fun publishTerms(values: List<UserTerm>) {
        val snapshot = values.toList()
        terms = snapshot
        val byPrefix = HashMap<IndexKey, MutableList<UserTerm>>()
        snapshot.forEach { term ->
            val shortcut = term.shortcut.lowercase(Locale.ROOT)
            for (length in 1..shortcut.length) {
                byPrefix.getOrPut(IndexKey(term.language, shortcut.substring(0, length))) { ArrayList() } += term
            }
        }
        val rank = compareByDescending<UserTerm> { it.frequency }
            .thenByDescending { it.lastUsedEpochMillis }.thenBy { it.id }
        prefixIndex = byPrefix.mapValues { (_, valuesForPrefix) -> valuesForPrefix.sortedWith(rank) }
        frequencyLookup = buildFrequencyLookup(snapshot)
        listIndex = snapshot.groupBy { it.language }.mapValues { (_, languageTerms) ->
            languageTerms.sortedWith(compareByDescending<UserTerm> { it.lastUsedEpochMillis }.thenBy { it.value })
        }
        allListIndex = snapshot.sortedWith(compareByDescending<UserTerm> { it.lastUsedEpochMillis }.thenBy { it.value })
    }

    private fun buildFrequencyLookup(values: List<UserTerm>): PersonalFrequencyLookup {
        val frequencies = HashMap<InputLanguage, MutableMap<String, Int>>()
        values.forEach { term ->
            val byValue = frequencies.getOrPut(term.language) { HashMap() }
            val key = frequencyKey(term.value, term.language)
            byValue.merge(key, term.frequency) { total, frequency ->
                (total.toLong() + frequency).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        val snapshot = frequencies.mapValues { (_, byValue) -> byValue.toMap() }
        return PersonalFrequencyLookup { words, language ->
            val byValue = snapshot[language].orEmpty()
            val result = HashMap<String, Int>(words.size.coerceAtMost(PersonalFrequencyStore.MAX_LOOKUP))
            words.asSequence().map(String::trim).filter(String::isNotEmpty)
                .distinct().take(PersonalFrequencyStore.MAX_LOOKUP).forEach { word ->
                    byValue[frequencyKey(word, language)]?.let { result[word] = it }
                }
            result
        }
    }

    private fun frequencyKey(value: String, language: InputLanguage): String =
        if (language == InputLanguage.ENGLISH) value.lowercase(Locale.ROOT) else value

    private data class IndexKey(val language: InputLanguage, val prefix: String)

    private companion object {
        const val MAX_SUGGESTION_LIMIT = 50
        val EXPORT_ASSOCIATED_DATA = "zeroinput:user-lexicon-export:v1".toByteArray(StandardCharsets.UTF_8)
    }
}
