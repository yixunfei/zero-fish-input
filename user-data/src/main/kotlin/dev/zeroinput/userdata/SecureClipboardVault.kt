package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

class SecureClipboardVault private constructor(
    private val store: EncryptedStore,
    private val indexStore: EncryptedStore,
    private val draftEntries: PrivateDraftEntries?,
    private val privateDraftWritesAllowed: () -> Boolean,
) {
    constructor(store: EncryptedStore, indexStore: EncryptedStore) : this(store, indexStore, null, { false })

    constructor(context: Context, privateDraftWritesAllowed: () -> Boolean = { false }) : this(
        EncryptedFileStore(context, "secure-clipboard.bin", SecurityAliases.SECURE_CLIPBOARD),
        EncryptedFileStore(context, "secure-clipboard-index.bin", SecurityAliases.SECURE_CLIPBOARD_INDEX),
        PrivateDraftEntries(context.applicationContext),
        privateDraftWritesAllowed,
    )

    private val lock = Any()
    private val generation = AtomicLong()
    /** The cached index contains only opaque entry IDs and timestamps. */
    private var indexCache: List<StoredSummary>? = null
    private var deletionPending = false
    private var indexNeedsRepair = false
    private var transactionFailed = false

    fun count(): Int = synchronized(lock) { loadIndexSafely().size + draftSummaries().size }

    fun summaries(): List<SecureClipboardSummary> = synchronized(lock) {
        (loadIndexSafely().map { SecureClipboardSummary(it.id, "", it.updatedAtEpochMillis) } +
            draftSummaries()).sortedByDescending(SecureClipboardSummary::updatedAtEpochMillis)
    }

    /** Revalidate after acquiring the vault lock; cancellation may occur while waiting. */
    fun read(
        id: String,
        grant: AuthenticationGrant,
        expectedGeneration: Long = captureGeneration(),
        isActive: () -> Boolean = { true },
    ): String? = synchronized(lock) {
        checkOperationActive(expectedGeneration, isActive)
        require(grant.consume()) { "Authentication expired or was already used" }
        val value = if (draftSummaries().any { it.id == id }) draftEntries?.read(id)
            else load().firstOrNull { it.id == id }?.value
        checkOperationActive(expectedGeneration, isActive)
        value
    }

    fun metadata(grant: AuthenticationGrant, expectedGeneration: Long = captureGeneration()): List<SecureClipboardMetadata> {
        val expected = expectedGeneration
        return synchronized(lock) {
            checkOperationActive(expected) { true }
            require(grant.consume()) { "Authentication expired or was already used" }
            val entries = load().sortedByDescending(SecureClipboardEntry::updatedAtEpochMillis)
            checkOperationActive(expected) { true }
            repairIndexIfNeeded(entries, expected)
            checkOperationActive(expected) { true }
            val drafts = draftSummaries().map { draft ->
                checkOperationActive(expected) { true }
                SecureClipboardMetadata(draft.id, "", checkNotNull(draftEntries).length(draft.id),
                    draft.updatedAtEpochMillis)
            }
            checkOperationActive(expected) { true }
            (entries.map { it.toMetadata() } + drafts).sortedByDescending(SecureClipboardMetadata::updatedAtEpochMillis)
        }
    }

    /** Capture before queuing an addition; deletion invalidates older requests. */
    fun captureGeneration(): Long = generation.get()

    fun add(
        label: String,
        value: String,
        grant: AuthenticationGrant,
        expectedGeneration: Long = captureGeneration(),
        isActive: () -> Boolean = { true },
    ): SecureClipboardMetadata {
        val characters = value.toCharArray()
        return try {
            add(label, characters, grant, expectedGeneration, isActive)
        } finally {
            characters.fill('\u0000')
        }
    }

    /**
     * Preferred addition path: the value travels as a CharArray so callers can
     * wipe their copy afterwards.  The String required by the persisted entry
     * is created inside the vault lock and is a documented JSON-model boundary.
     */
    fun add(
        label: String,
        value: CharArray,
        grant: AuthenticationGrant,
        expectedGeneration: Long = captureGeneration(),
        isActive: () -> Boolean = { true },
    ): SecureClipboardMetadata = synchronized(lock) {
        checkOperationActive(expectedGeneration, isActive)
        require(grant.consume()) { "Authentication expired or was already used" }
        val cleanValue = validateValue(String(value))
        val previous = load()
        val entries = previous.toMutableList()
        require(entries.size + draftSummaries().size < MAX_ENTRIES) { "Secure clipboard is full" }
        val entry = SecureClipboardEntry(
            id = UUID.randomUUID().toString(),
            label = validateLabel(label),
            value = cleanValue,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        entries += entry
        checkOperationActive(expectedGeneration, isActive)
        commit(previous, entries, expectedGeneration, isActive)
        entry.toMetadata()
    }

    /** Explicit private draft completion, authorized by the authenticated feature setting. */
    fun addPrivateDraft(value: CharArray, expectedGeneration: Long, isActive: () -> Boolean) = synchronized(lock) {
        val allowed = { privateDraftWritesAllowed() && isActive() }
        checkOperationActive(expectedGeneration, allowed)
        require(value.size <= MAX_VALUE_LENGTH && value.any { !it.isWhitespace() } && '\u0000' !in value)
        if (legacyIndexMissing()) {
            indexNeedsRepair = true
            indexCache = null
            throw IOException("Secure clipboard index needs repair")
        }
        val drafts = draftSummaries()
        require(loadIndexSafely().size + drafts.size < MAX_ENTRIES) { "Secure clipboard is full" }
        checkNotNull(draftEntries).add(UUID.randomUUID().toString(), value, allowKeyCreation = drafts.isEmpty()) {
            checkOperationActive(expectedGeneration, allowed)
        }
    }

    private fun legacyIndexMissing(): Boolean =
        store is EncryptedFileStore && indexStore is EncryptedFileStore &&
            store.hasCommittedData() && !indexStore.hasCommittedData()

    private fun draftSummaries(): List<SecureClipboardSummary> = draftEntries?.summaries().orEmpty()

    fun remove(id: String, grant: AuthenticationGrant, expectedGeneration: Long = captureGeneration()): Boolean {
        val expected = expectedGeneration
        return synchronized(lock) {
            checkOperationActive(expected) { true }
            require(grant.consume()) { "Authentication expired or was already used" }
            if (draftSummaries().any { it.id == id }) {
                if (!generation.compareAndSet(expected, expected + 1)) throw CancellationException("Clipboard operation cancelled")
                checkNotNull(draftEntries).remove(id)
                return@synchronized true
            }
            val previous = load()
            val entries = previous.toMutableList()
            checkOperationActive(expected) { true }
            val removed = entries.removeAll { it.id == id }
            if (removed) {
                if (!generation.compareAndSet(expected, expected + 1)) throw CancellationException("Clipboard operation cancelled")
                commit(previous, entries, expected + 1) { true }
            }
            removed
        }
    }

    fun clear(grant: AuthenticationGrant) {
        require(grant.consume()) { "Authentication expired or was already used" }
        purge()
    }

    /** Explicit user-confirmed deletion only; never reads plaintext or consumes a read grant. */
    fun purge() {
        generation.incrementAndGet()
        synchronized(lock) {
            deletionPending = true
            indexCache = emptyList()
            indexNeedsRepair = true
            var failure: Throwable? = null
            try {
                store.delete(deleteKey = true)
            } catch (error: Throwable) {
                failure = error
            }
            try {
                indexStore.delete(deleteKey = true)
            } catch (error: Throwable) {
                failure = failure?.also { it.addSuppressed(error) } ?: error
            }
            try {
                draftEntries?.purge()
            } catch (error: Throwable) {
                failure = failure?.also { it.addSuppressed(error) } ?: error
            }
            if (failure != null) {
                deletionPending = true
                throw failure
            }
            deletionPending = false
            indexNeedsRepair = false
            transactionFailed = false
        }
    }

    private fun checkOperationActive(expectedGeneration: Long, isActive: () -> Boolean) {
        if (generation.get() != expectedGeneration || !isActive() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Clipboard operation cancelled")
        }
        checkStorageAvailable()
    }

    private fun checkStorageAvailable() {
        if (deletionPending) throw IOException("Secure clipboard deletion is incomplete")
        if (transactionFailed) throw IOException("Secure clipboard transaction recovery is incomplete")
    }

    /**
     * Stage the body-free index first. The body's atomic write is the commit point:
     * cancellation after that point cannot turn a saved operation into a failure.
     * An interrupted process can leave a stale index, reconciled only after authentication.
     */
    private fun commit(
        previous: List<SecureClipboardEntry>,
        entries: List<SecureClipboardEntry>,
        expectedGeneration: Long,
        isActive: () -> Boolean,
    ) {
        var bodyWriteStarted = false
        try {
            checkOperationActive(expectedGeneration, isActive)
            persistIndex(entries)
            persist(entries) {
                checkOperationActive(expectedGeneration, isActive)
                bodyWriteStarted = true
            }
        } catch (error: Throwable) {
            // A clear owns the newer generation, including all cleanup; never restore old data over it.
            if (generation.get() == expectedGeneration && !deletionPending) {
                rollback(previous, bodyWriteStarted, expectedGeneration, error)
            }
            throw error
        }
    }

    private fun rollback(
        previous: List<SecureClipboardEntry>,
        restoreBody: Boolean,
        expectedGeneration: Long,
        failure: Throwable,
    ) {
        if (restoreBody) {
            try {
                persist(previous) { checkOperationActive(expectedGeneration) { true } }
            } catch (error: Throwable) {
                if (generation.get() != expectedGeneration) return
                transactionFailed = true
                failure.addSuppressed(error)
            }
        }
        if (generation.get() != expectedGeneration || deletionPending) return
        try {
            persistIndex(previous)
        } catch (error: Throwable) {
            indexCache = null
            indexNeedsRepair = true
            failure.addSuppressed(error)
        }
    }

    private fun load(): List<SecureClipboardEntry> {
        checkStorageAvailable()
        val bytes = store.read() ?: return emptyList()
        return try {
            withJsonReader(bytes) { reader ->
                var version: Long? = null
                var entries: List<SecureClipboardEntry>? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "format" -> version = nextCheckedLong(reader)
                        "entries" -> entries = readEntries(reader)
                        else -> invalid()
                    }
                }
                reader.endObject()
                require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
                require(version == FORMAT_VERSION.toLong() && entries != null) { "Unsupported secure clipboard format" }
                entries
            }
        } catch (_: java.io.IOException) {
            throw IOException("Invalid secure clipboard data")
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid secure clipboard data")
        } catch (_: IllegalStateException) {
            throw IOException("Invalid secure clipboard data")
        } finally {
            bytes.fill(0)
        }
    }

    private fun invalid(): Nothing = throw java.io.IOException("Invalid secure clipboard data")

    private fun nextCheckedLong(reader: android.util.JsonReader): Long {
        require(reader.peek() == android.util.JsonToken.NUMBER)
        return reader.nextString().toLongOrNull() ?: invalid()
    }

    private fun nextCheckedString(reader: android.util.JsonReader): String {
        require(reader.peek() == android.util.JsonToken.STRING)
        return reader.nextString()
    }

    private fun readEntries(reader: android.util.JsonReader): List<SecureClipboardEntry> {
        val entries = ArrayList<SecureClipboardEntry>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(entries.size < MAX_ENTRIES) { "Secure clipboard has too many entries" }
            var id: String? = null
            var label: String? = null
            var value: String? = null
            var updatedAt = 0L
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = nextCheckedString(reader)
                    "label" -> label = nextCheckedString(reader)
                    "value" -> value = nextCheckedString(reader)
                    "updatedAt" -> updatedAt = nextCheckedLong(reader)
                    else -> invalid()
                }
            }
            reader.endObject()
            entries += SecureClipboardEntry(
                id = validateId(id ?: invalid()),
                label = validateLabel(label ?: invalid()),
                value = validateValue(value ?: invalid()),
                updatedAtEpochMillis = updatedAt.coerceAtLeast(0L),
            )
        }
        reader.endArray()
        return entries
    }

    private fun loadIndexSafely(): List<StoredSummary> {
        checkStorageAvailable()
        if (indexNeedsRepair) throw IOException("Secure clipboard index needs repair")
        indexCache?.let { return it }
        // Do not cache a transient decryption/I/O failure as an empty index.
        // Keystore availability can briefly change while the device is
        // unlocking; caching the failure would make the panel appear empty
        // until a write or process restart repaired it.
        return try {
            loadIndex().also { indexCache = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IOException("Secure clipboard index unavailable")
        }
    }

    /** Authenticated body is authoritative, including after restart or a missing index. */
    private fun repairIndexIfNeeded(entries: List<SecureClipboardEntry>, expectedGeneration: Long) {
        val expected = entries.map { StoredSummary(it.id, it.updatedAtEpochMillis) }.sortedBy { it.id }
        val actual = try {
            if (indexNeedsRepair || legacyIndexMissing()) null else loadIndexSafely().sortedBy { it.id }
        } catch (_: IOException) {
            null
        }
        checkOperationActive(expectedGeneration) { true }
        if (actual != expected) persistIndex(entries)
    }

    private fun loadIndex(): List<StoredSummary> {
        val bytes = indexStore.read() ?: run {
            if (legacyIndexMissing()) throw IOException("Secure clipboard index needs repair")
            return emptyList()
        }
        return try {
            withJsonReader(bytes) { reader ->
                var version: Long? = null
                var summaries: List<StoredSummary>? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "format" -> version = nextCheckedLong(reader)
                        "entries" -> summaries = readIndexEntries(reader)
                        else -> invalid()
                    }
                }
                reader.endObject()
                require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
                require(version == INDEX_FORMAT_VERSION.toLong() && summaries != null) { "Unsupported secure clipboard index" }
                summaries
            }
        } catch (_: java.io.IOException) {
            throw IOException("Invalid secure clipboard index")
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid secure clipboard index")
        } catch (_: IllegalStateException) {
            throw IOException("Invalid secure clipboard index")
        } finally {
            bytes.fill(0)
        }
    }

    private fun readIndexEntries(reader: android.util.JsonReader): List<StoredSummary> {
        val summaries = ArrayList<StoredSummary>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(summaries.size < MAX_ENTRIES) { "Secure clipboard index has too many entries" }
            var id: String? = null
            var updatedAt = 0L
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = nextCheckedString(reader)
                    "updatedAt" -> updatedAt = nextCheckedLong(reader)
                    else -> invalid()
                }
            }
            reader.endObject()
            summaries += StoredSummary(
                id = validateId(id ?: invalid()),
                updatedAtEpochMillis = updatedAt.coerceAtLeast(0L),
            )
        }
        reader.endArray()
        return summaries
    }

    private fun persist(entries: List<SecureClipboardEntry>, beforeWrite: () -> Unit = {}) {
        val root = JSONObject().apply {
            put("format", FORMAT_VERSION)
            put("entries", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("id", entry.id)
                        put("label", entry.label)
                        put("value", entry.value)
                        put("updatedAt", entry.updatedAtEpochMillis)
                    })
                }
            })
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            beforeWrite()
            store.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun persistIndex(entries: List<SecureClipboardEntry>) {
        checkStorageAvailable()
        val expectedGeneration = generation.get()
        indexCache = null
        indexNeedsRepair = true
        val summaries = entries.map { entry ->
            StoredSummary(entry.id, entry.updatedAtEpochMillis)
        }
        val root = JSONObject().apply {
            put("format", INDEX_FORMAT_VERSION)
            put("entries", JSONArray().apply {
                summaries.forEach { entry ->
                    put(JSONObject().apply {
                        put("id", entry.id)
                        put("updatedAt", entry.updatedAtEpochMillis)
                    })
                }
            })
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            indexStore.write(bytes)
        } finally {
            bytes.fill(0)
        }
        if (generation.get() == expectedGeneration) {
            indexCache = summaries
            indexNeedsRepair = false
        }
    }

    private fun validateId(value: String): String = value.also {
        require(ID_PATTERN.matches(it)) { "Invalid secure clipboard entry id" }
    }

    private fun validateLabel(value: String): String = value.trim().take(MAX_LABEL_LENGTH)

    private fun validateValue(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= MAX_VALUE_LENGTH) { "Invalid secure clipboard value" }
        require('\u0000' !in it) { "Secure clipboard value contains a null byte" }
    }

    private fun SecureClipboardEntry.toMetadata() = SecureClipboardMetadata(
        id = id,
        label = label,
        valueLength = value.length,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    companion object {
        const val MAX_LABEL_LENGTH = 64
        const val MAX_VALUE_LENGTH = 8_192
        private const val FORMAT_VERSION = 1
        private const val INDEX_FORMAT_VERSION = 1
        private const val MAX_ENTRIES = 100
        private val ID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }

    private data class StoredSummary(
        val id: String,
        val updatedAtEpochMillis: Long,
    )
}

data class SecureClipboardEntry(
    val id: String,
    val label: String,
    val value: String,
    val updatedAtEpochMillis: Long,
)

data class SecureClipboardSummary(
    val id: String,
    val displayName: String,
    val updatedAtEpochMillis: Long,
)

data class SecureClipboardMetadata(
    val id: String,
    val label: String,
    val valueLength: Int,
    val updatedAtEpochMillis: Long,
)
