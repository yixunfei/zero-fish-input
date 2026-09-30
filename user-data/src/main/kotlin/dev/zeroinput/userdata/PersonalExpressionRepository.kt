package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Background-only operations. Plaintext snapshots are owned by the visible caller, never cached here. */
class PersonalExpressionRepository(
    private val store: EncryptedStore,
    private val exportCipher: EncryptedExportCipher = KeyStoreExportCipher(SecurityAliases.EXPRESSIONS_EXPORT),
) {
    constructor(context: Context) : this(
        EncryptedFileStore(context, "personal-expressions.bin", SecurityAliases.EXPRESSIONS),
        KeyStoreExportCipher(SecurityAliases.EXPRESSIONS_EXPORT),
    )

    private val lock = Any()
    private val deletionGeneration = AtomicLong()
    private val revision = AtomicLong()
    private var deletionPending = false

    fun generation(): Long = deletionGeneration.get()
    fun revision(): Long = revision.get()

    fun read(): PersonalExpressions = snapshot().data

    /** Creates a device-bound encrypted export; plaintext is kept only in memory. */
    fun exportEncrypted(): ByteArray {
        val plaintext = synchronized(lock) { PersonalExpressionFormat.encode(load()) }
        return try {
            exportCipher.encrypt(plaintext, EXPORT_ASSOCIATED_DATA)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Merges a device-bound export into the local custom expressions. */
    fun importEncrypted(bytes: ByteArray, expected: Long = generation(), isCurrent: () -> Boolean = { true }): Int {
        if (bytes.size > ExpressionLimits.BYTES + ENVELOPE_OVERHEAD) fail(ExpressionFailure.INVALID)
        val capturedGeneration = expected
        val plaintext = try {
            exportCipher.decrypt(bytes, EXPORT_ASSOCIATED_DATA)
        } catch (_: Exception) {
            fail(ExpressionFailure.CORRUPT)
        }
        return try {
            val imported = PersonalExpressionFormat.decode(plaintext)
            update(capturedGeneration, isCurrent) { current ->
                val existing = current.custom.associateBy { it.value }.toMutableMap()
                imported.custom.forEach { item ->
                    if (item.value !in existing) existing[item.value] = item.copy(id = UUID.randomUUID().toString())
                }
                if (existing.size > ExpressionLimits.CUSTOM) fail(ExpressionFailure.CAPACITY)
                val favorites = (current.favorites + imported.favorites)
                if (favorites.size > ExpressionLimits.FAVORITES) fail(ExpressionFailure.CAPACITY)
                PersonalExpressions(existing.values.toList(), favorites)
            }
            imported.custom.size
        } finally {
            plaintext.fill(0)
        }
    }

    fun snapshot(expected: Long = generation(), isCurrent: () -> Boolean = { true }): PersonalExpressionSnapshot =
        synchronized(lock) {
            ensureCurrent(expected, isCurrent)
            val data = load()
            ensureCurrent(expected, isCurrent)
            PersonalExpressionSnapshot(data, revision())
        }

    fun save(
        id: String?, value: String, name: String, keywords: String, group: String,
        expected: Long = generation(), isCurrent: () -> Boolean = { true },
    ): PersonalExpression {
        val entry = PersonalExpression(id ?: UUID.randomUUID().toString(), value, name.trim(), keywords.trim(), group)
        ExpressionLimits.validate(entry)
        update(expected, isCurrent) { current ->
            if (current.custom.any { it.value == value && it.id != entry.id }) fail(ExpressionFailure.DUPLICATE)
            if (id != null && current.custom.none { it.id == id }) fail(ExpressionFailure.CANCELLED)
            val previous = current.custom.firstOrNull { it.id == entry.id }
            val custom = current.custom.filterNot { it.id == entry.id } + entry
            val favorites = if (previous != null && previous.value in current.favorites) {
                current.favorites - previous.value + value
            } else current.favorites
            PersonalExpressions(custom, favorites)
        }
        return entry
    }

    fun remove(id: String, expected: Long = generation(), isCurrent: () -> Boolean = { true }) {
        update(expected, isCurrent) { current ->
            val entry = current.custom.firstOrNull { it.id == id } ?: return@update current
            current.copy(custom = current.custom.filterNot { it.id == id }, favorites = current.favorites - entry.value)
        }
    }

    fun favorite(value: String, selected: Boolean, expected: Long = generation(), isCurrent: () -> Boolean = { true }) {
        if (!ExpressionLimits.validText(value)) fail(ExpressionFailure.INVALID)
        update(expected, isCurrent) { current ->
            current.copy(favorites = if (selected) current.favorites + value else current.favorites - value)
        }
    }

    /** Invalidates queued operations before waiting for any active encrypted write. */
    fun clear() {
        deletionGeneration.incrementAndGet()
        revision.incrementAndGet()
        synchronized(lock) {
            deletionPending = true
            try {
                store.delete(deleteKey = true)
                if (exportCipher.hasKey()) exportCipher.deleteKey()
                deletionPending = false
            } catch (_: Exception) {
                fail(ExpressionFailure.STORAGE)
            }
        }
    }

    private fun update(expected: Long, isCurrent: () -> Boolean, change: (PersonalExpressions) -> PersonalExpressions) {
        synchronized(lock) {
            ensureCurrent(expected, isCurrent)
            val updated = change(load())
            val bytes = PersonalExpressionFormat.encode(updated)
            try {
                ensureCurrent(expected, isCurrent)
                store.write(bytes)
                revision.incrementAndGet()
            } catch (error: ExpressionException) {
                throw error
            } catch (_: Exception) {
                fail(ExpressionFailure.STORAGE)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun load(): PersonalExpressions {
        if (deletionPending) fail(ExpressionFailure.STORAGE)
        val bytes = try { store.read() } catch (_: Exception) { fail(ExpressionFailure.STORAGE) }
            ?: return PersonalExpressions()
        return try { PersonalExpressionFormat.decode(bytes) } finally { bytes.fill(0) }
    }

    private fun ensureCurrent(expected: Long, isCurrent: () -> Boolean) {
        if (expected != generation() || !isCurrent() || Thread.currentThread().isInterrupted) fail(ExpressionFailure.CANCELLED)
        if (deletionPending) fail(ExpressionFailure.STORAGE)
    }

    private fun fail(reason: ExpressionFailure): Nothing = throw ExpressionException(reason)

    private companion object {
        val EXPORT_ASSOCIATED_DATA = "zeroinput-expression-export-v1".toByteArray(StandardCharsets.UTF_8)
        const val ENVELOPE_OVERHEAD = 64
    }
}
