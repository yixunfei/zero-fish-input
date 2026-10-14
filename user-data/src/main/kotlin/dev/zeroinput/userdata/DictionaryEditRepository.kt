package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Worker-only, serialized edits. No plaintext file or repository-level plaintext cache. */
class DictionaryEditRepository(private val store: EncryptedStore) {
    constructor(context: Context) : this(EncryptedFileStore(context, "dictionary-edits.bin", SecurityAliases.DICTIONARY_EDITS))
    private val lock = Any()
    private val generation = AtomicLong()
    private var deletionPending = false
    fun generation(): Long = generation.get()
    var onPublished: () -> Unit = {}

    fun read(): DictionaryEdits = synchronized(lock) { load() }

    fun rename(id: String, name: String, expected: Long = generation(), isCurrent: () -> Boolean = { true }) {
        DictionaryEditLimits.id(id)
        DictionaryEditLimits.text(name, 160)
        update(expected, isCurrent) { it.copy(names = it.names + (id to name)) }
    }

    fun save(entry: DictionaryEdit, expected: Long = generation(), isCurrent: () -> Boolean = { true }) {
        update(expected, isCurrent) { current ->
            val entries = current.entries.filterNot { it.dictionaryId == entry.dictionaryId && it.key == entry.key } + entry
            require(entries.none { other -> other !== entry && other.dictionaryId == entry.dictionaryId &&
                other.replacement != null && entry.replacement != null &&
                other.replacement.text == entry.replacement.text && other.replacement.reading == entry.replacement.reading
            }) { "Duplicate dictionary edit" }
            current.copy(entries = entries)
        }
    }

    fun undo(id: String, key: String, expected: Long = generation(), isCurrent: () -> Boolean = { true }) {
        update(expected, isCurrent) { current ->
            current.copy(entries = current.entries.filterNot { it.dictionaryId == id && it.key == key })
        }
    }

    fun replaceAddition(old: DictionaryEdit, value: DictionaryEdit, expected: Long = generation()) {
        require(old.original == null && value.original == null && old.dictionaryId == value.dictionaryId)
        update(expected, { true }) { current ->
            current.copy(entries = current.entries.filterNot { it.dictionaryId == old.dictionaryId && it.key == old.key } + value)
        }
    }

    fun reset(id: String) {
        DictionaryEditLimits.id(id)
        val expected = generation.incrementAndGet()
        update(expected, { true }) { it.copy(names = it.names - id, entries = it.entries.filterNot { row -> row.dictionaryId == id }) }
    }

    fun clear() {
        generation.incrementAndGet()
        synchronized(lock) {
            deletionPending = true
            store.delete(deleteKey = true)
            deletionPending = false
        }
        onPublished()
    }

    private fun load(): DictionaryEdits {
        check(!deletionPending) { "Dictionary edits unavailable" }
        val bytes = store.read() ?: return DictionaryEdits()
        return try { DictionaryEditFormat.decode(bytes) } finally { bytes.fill(0) }
    }

    private fun update(expected: Long, isCurrent: () -> Boolean, transform: (DictionaryEdits) -> DictionaryEdits) {
        synchronized(lock) {
            ensureCurrent(expected, isCurrent)
            val bytes = DictionaryEditFormat.encode(transform(load()))
            try {
                ensureCurrent(expected, isCurrent)
                store.write(bytes)
            } finally { bytes.fill(0) }
        }
        onPublished()
    }

    private fun ensureCurrent(expected: Long, isCurrent: () -> Boolean) {
        if (expected != generation() || !isCurrent() || Thread.currentThread().isInterrupted) throw CancellationException()
        check(!deletionPending) { "Dictionary edits unavailable" }
    }
}
