package dev.zeroinput.ime.dictionaries

import android.content.Context
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.engine.rime.BundledDictionaryReader
import dev.zeroinput.languagepack.PublicDictionaryStore
import dev.zeroinput.userdata.DictionaryEdit
import dev.zeroinput.userdata.DictionaryEditRepository
import dev.zeroinput.userdata.DictionaryWord
import java.io.InputStream

internal data class DictionaryEntryPage(val entries: List<DictionaryEdit>, val hasMore: Boolean)

/** Bounded results, no plaintext index/cache; scans only on the cancellable settings worker. */
internal class DictionaryEntrySearch(
    private val readOriginal: (String, (InputStream) -> Unit) -> Unit,
    private val edits: DictionaryEditRepository,
) {
    constructor(context: Context, originals: PublicDictionaryStore, edits: DictionaryEditRepository) : this(
        { id, consume ->
            if (!id.startsWith("builtin_") || originals.list().any { it.id == id }) {
                originals.withDictionaryInput(id, consume)
            } else BundledDictionaryReader(context.applicationContext).withInput(id.removePrefix("builtin_"), consume)
        }, edits,
    )

    fun search(id: String, query: String, offset: Int, changesOnly: Boolean): DictionaryEntryPage {
        require(query.length <= 256 && offset in 0..5_000_000)
        checkCancelled()
        val changes = edits.read().entries.filter { it.dictionaryId == id }
        val changedKeys = changes.mapTo(HashSet()) { it.key }
        val result = ArrayList<DictionaryEdit>()
        var found = 0
        fun add(edit: DictionaryEdit) {
            checkCancelled()
            val word = edit.replacement ?: edit.original ?: return
            if (!word.text.contains(query, true) && !word.reading.replace(" ", "").contains(query.replace(" ", ""), true)) return
            if (found++ >= offset) result += edit
            if (result.size > PAGE_SIZE) throw PageFull()
        }
        try {
            // Retained overrides remain active even when an upstream revision drops
            // their original row. Always expose them so they can be edited or undone.
            changes.forEach(::add)
            checkCancelled()
            if (!changesOnly) readOriginal(id) { input ->
                RimeTextDictionaryParser().parse(input) { entry ->
                    val word = DictionaryWord(entry.text, entry.reading, entry.weight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    val row = DictionaryEdit(id, word, word)
                    if (row.key !in changedKeys) add(row)
                }
            }
        } catch (_: PageFull) { /* Public data was already verified before this paged read. */ }
        return DictionaryEntryPage(result.take(PAGE_SIZE), result.size > PAGE_SIZE)
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
    }

    private class PageFull : RuntimeException(null, null, false, false)
    companion object { const val PAGE_SIZE = 40 }
}
