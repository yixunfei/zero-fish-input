package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

class SecureClipboardVault(
    private val store: EncryptedStore,
    private val indexStore: EncryptedStore,
) {
    constructor(context: Context) : this(
        EncryptedFileStore(context, "secure-clipboard.bin", SecurityAliases.SECURE_CLIPBOARD),
        EncryptedFileStore(context, "secure-clipboard-index.bin", SecurityAliases.SECURE_CLIPBOARD_INDEX),
    )

    private val lock = Any()
    private val generation = AtomicLong()
    /** Index entries contain no labels or正文 and are safe to cache in-process. */
    private var indexCache: List<StoredSummary>? = null
    private var deletionPending = false
    private var indexNeedsRepair = false

    fun count(): Int = synchronized(lock) { loadIndexSafely().size }

    fun summaries(): List<SecureClipboardSummary> = synchronized(lock) {
        loadIndexSafely().sortedByDescending(StoredSummary::updatedAtEpochMillis)
            .mapIndexed { index, summary ->
                SecureClipboardSummary(
                    id = summary.id,
                    displayName = "",
                    updatedAtEpochMillis = summary.updatedAtEpochMillis,
                )
            }
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
        val entries = load()
        checkOperationActive(expectedGeneration, isActive)
        entries.firstOrNull { it.id == id }?.value.also { value ->
            if (value == null) persistIndex(entries)
        }
    }

    fun metadata(grant: AuthenticationGrant, expectedGeneration: Long = captureGeneration()): List<SecureClipboardMetadata> {
        val expected = expectedGeneration
        return synchronized(lock) {
            checkOperationActive(expected) { true }
            require(grant.consume()) { "Authentication expired or was already used" }
            val entries = load().sortedByDescending(SecureClipboardEntry::updatedAtEpochMillis)
            checkOperationActive(expected) { true }
            persistIndex(entries)
            checkOperationActive(expected) { true }
            entries.map { it.toMetadata() }
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
    ): SecureClipboardMetadata = synchronized(lock) {
        checkOperationActive(expectedGeneration, isActive)
        require(grant.consume()) { "Authentication expired or was already used" }
        val cleanValue = validateValue(value)
        val entries = load().toMutableList()
        require(entries.size < MAX_ENTRIES) { "Secure clipboard is full" }
        val entry = SecureClipboardEntry(
            id = UUID.randomUUID().toString(),
            label = validateLabel(label),
            value = cleanValue,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        entries += entry
        checkOperationActive(expectedGeneration, isActive)
        persist(entries) { checkOperationActive(expectedGeneration, isActive) }
        checkOperationActive(expectedGeneration, isActive)
        persistIndex(entries)
        checkOperationActive(expectedGeneration, isActive)
        entry.toMetadata()
    }

    fun remove(id: String, grant: AuthenticationGrant, expectedGeneration: Long = captureGeneration()): Boolean {
        val expected = expectedGeneration
        return synchronized(lock) {
            checkOperationActive(expected) { true }
            require(grant.consume()) { "Authentication expired or was already used" }
            val entries = load().toMutableList()
            checkOperationActive(expected) { true }
            val removed = entries.removeAll { it.id == id }
            if (removed) {
                if (!generation.compareAndSet(expected, expected + 1)) throw CancellationException("Clipboard operation cancelled")
                persist(entries) { checkOperationActive(expected + 1) { true } }
                checkOperationActive(expected + 1) { true }
                persistIndex(entries)
                checkOperationActive(expected + 1) { true }
            }
            removed
        }
    }

    fun clear(grant: AuthenticationGrant) {
        require(grant.consume()) { "Authentication expired or was already used" }
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
            if (failure != null) {
                deletionPending = true
                throw failure
            }
            deletionPending = false
            indexNeedsRepair = false
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
    }

    private fun load(): List<SecureClipboardEntry> {
        checkStorageAvailable()
        val bytes = store.read() ?: return emptyList()
        return try {
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(root.getInt("format") == FORMAT_VERSION) { "Unsupported secure clipboard format" }
            val array = root.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES) { "Secure clipboard has too many entries" }
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                SecureClipboardEntry(
                    id = validateId(item.getString("id")),
                    label = validateLabel(item.getString("label")),
                    value = validateValue(item.getString("value")),
                    updatedAtEpochMillis = item.getLong("updatedAt").coerceAtLeast(0L),
                )
            }
        } catch (_: JSONException) {
            throw IOException("Invalid secure clipboard data")
        } finally {
            bytes.fill(0)
        }
    }

    private fun loadIndexSafely(): List<StoredSummary> {
        checkStorageAvailable()
        if (indexNeedsRepair) return emptyList()
        indexCache?.let { return it }
        // Do not cache a transient decryption/I/O failure as an empty index.
        // Keystore availability can briefly change while the device is
        // unlocking; caching the failure would make the panel appear empty
        // until a write or process restart repaired it.
        return runCatching(::loadIndex)
            .onSuccess { indexCache = it }
            .getOrDefault(emptyList())
    }

    private fun loadIndex(): List<StoredSummary> {
        val bytes = indexStore.read() ?: return emptyList()
        return try {
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(root.getInt("format") == INDEX_FORMAT_VERSION) { "Unsupported secure clipboard index" }
            val array = root.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES) { "Secure clipboard index has too many entries" }
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                StoredSummary(
                    id = validateId(item.getString("id")),
                    updatedAtEpochMillis = item.getLong("updatedAt").coerceAtLeast(0L),
                )
            }
        } finally {
            bytes.fill(0)
        }
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
            indexCache = null
            indexNeedsRepair = true
        } finally {
            bytes.fill(0)
        }
    }

    private fun persistIndex(entries: List<SecureClipboardEntry>) {
        checkStorageAvailable()
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
        indexCache = summaries
        indexNeedsRepair = false
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
