package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.LearnedSuggestionSource
import dev.zeroinput.engine.api.PersonalFrequencyStore
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** All methods that access the store must run on a background worker. */
class UserLexiconRepository(private val store: EncryptedStore) : LearnedSuggestionSource, PagedPersonalizationStore, PersonalFrequencyStore {
    constructor(context: Context) : this(EncryptedFileStore(
        context = context,
        fileName = "user-lexicon.bin",
        keyAlias = SecurityAliases.USER_DATA,
    ))

    private val lock = Any()
    private val generation = AtomicLong(0L)
    private var terms: List<UserTerm>? = null
    private var deletionPending = false

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
            val normalized = prefix.trim().lowercase()
            if (normalized.isEmpty() || limit == 0) return@synchronized PersonalSuggestionPage()
            val values = loadTerms().asSequence()
                .filter { it.language == language && it.shortcut.lowercase().startsWith(normalized) }
                .sortedWith(compareBy<UserTerm> { !it.shortcut.equals(normalized, ignoreCase = true) }
                    .thenByDescending { it.frequency }.thenByDescending { it.lastUsedEpochMillis }.thenBy { it.id })
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
        synchronized(lock) {
            val wanted = words.asSequence().map(String::trim).filter(String::isNotEmpty)
                .distinct().take(PersonalFrequencyStore.MAX_LOOKUP).toSet()
            if (wanted.isEmpty()) {
                emptyMap()
            } else {
                val totals = HashMap<String, Int>(wanted.size)
                for (term in loadTerms()) {
                    if (term.language == language && term.value in wanted) {
                        totals.merge(term.value, term.frequency) { total, frequency ->
                            (total.toLong() + frequency).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        }
                    }
                }
                totals
            }
        }

    fun list(language: InputLanguage? = null): List<UserTerm> = synchronized(lock) {
        loadTerms().asSequence()
            .filter { language == null || it.language == language }
            .sortedWith(compareByDescending<UserTerm> { it.lastUsedEpochMillis }.thenBy { it.value })
            .toList()
    }

    fun addPhrase(shortcut: String, value: String, language: InputLanguage): UserTerm {
        val expected = generation.get()
        val cleanShortcut = UserLexiconFormat.validateShortcut(shortcut)
        val cleanValue = UserLexiconFormat.validateValue(value)
        return synchronized(lock) {
            ensureCurrent(expected)
            val current = loadTerms().toMutableList()
            val index = findPhrase(current, cleanShortcut, cleanValue, language)
            if (index < 0 && current.size >= MAX_TERMS) capacityExceeded()
            val now = System.currentTimeMillis()
            val term = if (index >= 0) current[index].copy(lastUsedEpochMillis = now) else {
                UserTerm(UUID.randomUUID().toString(), cleanShortcut, cleanValue, language, 1, now)
            }
            if (index >= 0) current[index] = term else current += term
            persist(current, expected)
            term
        }
    }

    override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) {
        if (!learningAllowed) return
        val expected = generation.get()
        val cleanShortcut = shortcut.trim().lowercase()
        val cleanValue = value.trim()
        if (!isLearnable(cleanShortcut, 1, MAX_SHORTCUT_LENGTH) || !isLearnable(cleanValue, 2, MAX_VALUE_LENGTH)) return
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
            terms = emptyList()
            deletionPending = true
            store.delete(deleteKey = true)
            deletionPending = false
        }
    }

    fun exportJson(): ByteArray = synchronized(lock) {
        UserLexiconFormat.serialize(loadTerms()).toString().toByteArray(StandardCharsets.UTF_8)
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
        val bytes = store.read() ?: return emptyList<UserTerm>().also { terms = it }
        return try {
            UserLexiconFormat.parse(bytes).also { terms = it }
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
            store.write(bytes)
        } finally {
            bytes.fill(0)
        }
        terms = if (expected == generation.get()) values.toList() else emptyList()
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

    private companion object {
        const val MAX_SUGGESTION_LIMIT = 50
    }
}
