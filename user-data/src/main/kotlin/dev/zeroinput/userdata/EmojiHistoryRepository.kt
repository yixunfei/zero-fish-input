package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import java.util.concurrent.atomic.AtomicLong

/** Background-only encrypted history, with no process-wide plaintext cache. */
class EmojiHistoryRepository(private val store: EncryptedStore) {
    constructor(context: Context) : this(EncryptedFileStore(context, "emoji-history.bin", SecurityAliases.EMOJI_HISTORY))

    private val lock = Any()
    private val clearRevision = AtomicLong()
    private var deletionPending = false
    private var lastUsedHighWater = 0L

    fun currentRevision(): Long = clearRevision.get()

    fun record(emoji: String) { recordIfRevision(emoji, currentRevision()) }

    fun recordIfRevision(emoji: String, expectedRevision: Long, isCurrent: () -> Boolean = { true }): Boolean =
        synchronized(lock) {
            if (expectedRevision != currentRevision() || !isCurrent()) return@synchronized false
            if (!ExpressionLimits.validText(emoji)) throw ExpressionException(ExpressionFailure.INVALID)
            val current = load().associateByTo(linkedMapOf(), EmojiUsage::value)
            val old = current[emoji]
            val nextLastUsed = maxOf(System.currentTimeMillis(), lastUsedHighWater + 1)
            current[emoji] = EmojiUsage(emoji, ((old?.count ?: 0) + 1).coerceAtMost(EmojiHistoryFormat.MAX_COUNT), nextLastUsed)
            val updated = current.values.sortedByDescending { it.lastUsed }.take(EmojiHistoryFormat.MAX_ENTRIES)
            val bytes = EmojiHistoryFormat.encode(updated)
            try {
                if (expectedRevision != currentRevision() || !isCurrent()) return@synchronized false
                store.write(bytes)
            } finally { bytes.fill(0) }
            // Raise the high-water only after the write succeeded; a failed or
            // stale write must not skip timestamps for later records.
            lastUsedHighWater = nextLastUsed
            true
        }

    fun recent(limit: Int = 32, isCurrent: () -> Boolean = { true }): List<String> = synchronized(lock) {
        require(limit in 0..EmojiHistoryFormat.MAX_ENTRIES)
        if (!isCurrent()) return@synchronized emptyList()
        val revision = currentRevision()
        val loaded = load()
        if (!isCurrent() || revision != currentRevision()) return@synchronized emptyList()
        loaded.sortedWith(compareByDescending<EmojiUsage> { it.lastUsed }.thenByDescending { it.count })
            .take(limit).map { it.value }
    }

    fun clear() {
        clearRevision.incrementAndGet()
        synchronized(lock) {
            deletionPending = true
            try {
                store.delete(deleteKey = true)
                lastUsedHighWater = 0L
                // Only a successful delete clears the fail-closed marker.  On
                // failure the store may be half-deleted, so load()/record()
                // keep rejecting access until a retried clear() succeeds.
                deletionPending = false
            } catch (error: Throwable) {
                deletionPending = true
                throw error
            }
        }
    }

    private fun load(): List<EmojiUsage> {
        if (deletionPending) throw ExpressionException(ExpressionFailure.STORAGE)
        val bytes = store.read() ?: return emptyList()
        return try {
            EmojiHistoryFormat.decode(bytes).also { values ->
                lastUsedHighWater = maxOf(lastUsedHighWater, values.maxOfOrNull { it.lastUsed } ?: 0L)
            }
        } finally { bytes.fill(0) }
    }
}
