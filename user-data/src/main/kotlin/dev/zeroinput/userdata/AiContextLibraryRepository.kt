package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.ai.api.AiContextLibrary
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases

/** Serial atomic edits keep role deletion and removal of scoped memories indivisible. No plaintext cache. */
class AiContextLibraryRepository(private val store: EncryptedStore) {
    constructor(context: Context) : this(EncryptedFileStore(context, "ai-context-library.bin", SecurityAliases.AI_CONTEXT_LIBRARY))
    private val lock = Any()
    private var deletionFailed = false

    fun read(current: () -> Boolean = { true }): AiContextLibrary = synchronized(lock) {
        checkCurrent(current)
        val bytes = store.read() ?: return@synchronized AiContextLibrary().also { checkCurrent(current) }
        try { AiContextLibraryFormat.decode(bytes).also { checkCurrent(current) } }
        finally { bytes.fill(0) }
    }

    fun write(expectedRevision: Long, value: AiContextLibrary, current: () -> Boolean): AiContextLibrary = synchronized(lock) {
        val previous = read(current)
        check(previous.revision == expectedRevision && expectedRevision < Long.MAX_VALUE) { "Context edit expired" }
        val next = AiContextLibrary(expectedRevision + 1, value.personas, value.memories)
        val bytes = AiContextLibraryFormat.encode(next)
        try { checkCurrent(current); store.write(bytes) } finally { bytes.fill(0) }
        checkCurrent(current)
        next
    }

    fun clear() = synchronized(lock) {
        deletionFailed = true
        store.delete(deleteKey = true)
        deletionFailed = false
    }

    private fun checkCurrent(current: () -> Boolean) {
        check(!deletionFailed && current()) { "Context operation expired" }
    }
}
