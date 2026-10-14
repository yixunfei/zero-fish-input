package dev.zeroinput.ime.dictionaries

import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.DictionaryEdit
import dev.zeroinput.userdata.DictionaryEditRepository
import dev.zeroinput.userdata.DictionaryWord
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class DictionaryEntrySearchTest {
    private val id = "fixture"
    private val edits = DictionaryEditRepository(MemoryStore())
    private var originalReads = 0

    @Test fun retainedEditsRemainSearchableWhenRedownloadRemovesTheirOriginals() {
        val removed = DictionaryWord("旧词", "jiu ci", 1)
        val replacement = DictionaryWord("修改词", "xiu gai ci", 30)
        val change = DictionaryEdit(id, removed, replacement)
        edits.save(change)
        val search = search(listOf(DictionaryWord("新词", "xin ci", 2)))

        assertEquals(listOf(change), search.search(id, "修改词", 0, false).entries)
        assertEquals(listOf(change), search.search(id, "XIUGAI", 0, false).entries)
        assertEquals(2, search.search(id, "", 0, false).entries.size)
        edits.undo(id, change.key)
        assertTrue(search.search(id, "修改词", 0, false).entries.isEmpty())
    }

    @Test fun localChangesAndPublicRowsSharePagesWithoutRepeatingEditedOriginals() {
        val originals = (0 until 85).map { DictionaryWord("公开词$it", "gong kai ci", it) }
        val changed = DictionaryEdit(id, originals.first(), originals.first().copy(text = "修改词", weight = 100))
        val deleted = DictionaryEdit(id, originals[40], null)
        val added = DictionaryEdit(id, null, DictionaryWord("新增词", "xin zeng ci", 20))
        listOf(changed, deleted, added).forEach { edits.save(it) }
        edits.save(added.copy(dictionaryId = "other"))
        val search = search(originals)
        val first = search.search(id, "", 0, false)
        val second = search.search(id, "", 40, false)
        val last = search.search(id, "", 80, false)
        val entries = first.entries + second.entries + last.entries

        assertEquals(40, first.entries.size)
        assertEquals(40, second.entries.size)
        assertEquals(6, last.entries.size)
        assertTrue(first.hasMore && second.hasMore)
        assertFalse(last.hasMore)
        assertEquals(86, entries.map { it.key }.distinct().size)
        assertEquals(changed, entries.single { it.key == changed.key })
        assertEquals(deleted, entries.single { it.key == deleted.key })
        assertEquals(added, entries.single { it.key == added.key })
    }

    @Test fun changesOnlyCanUndoTombstonesWithoutReadingUnavailableOriginals() {
        val change = DictionaryEdit(id, DictionaryWord("已删词", "yi shan ci", 3), null)
        edits.save(change)
        val search = DictionaryEntrySearch({ _, _ -> error("Original unavailable") }, edits)
        assertEquals(listOf(change), search.search(id, "yishan", 0, true).entries)
        edits.undo(id, change.key)
        assertTrue(search.search(id, "", 0, true).entries.isEmpty())
    }

    @Test fun cancellationBeforeSearchDoesNotReadOriginals() {
        val search = search(listOf(DictionaryWord("公开词", "gong kai ci", 1)))
        try {
            Thread.currentThread().interrupt()
            assertThrows(CancellationException::class.java) { search.search(id, "", 0, false) }
            assertEquals(0, originalReads)
        } finally { Thread.interrupted() }
    }

    private fun search(words: List<DictionaryWord>) = DictionaryEntrySearch({ _, consume ->
        originalReads++
        val data = "---\nname: fixture\n...\n" + words.joinToString("\n") { "${it.text}\t${it.reading}\t${it.weight}" }
        data.byteInputStream().use(consume)
    }, edits)

    private class MemoryStore : EncryptedStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}
