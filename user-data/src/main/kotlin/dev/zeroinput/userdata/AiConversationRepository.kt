package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.ai.api.AiConversation
import dev.zeroinput.ai.api.AiConversationSummary
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiMessage
import dev.zeroinput.ai.api.AiRole
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class AiConversationRepository(private val store: EncryptedStore) {
    constructor(context: Context) : this(
        EncryptedFileStore(context, "ai-conversations.bin", SecurityAliases.AI_CONVERSATIONS),
    )

    private val lock = Any()
    private var deletionFailed = false

    fun list(): List<AiConversation> = synchronized(lock) {
        check(!deletionFailed) { "AI data unavailable" }
        load()
    }

    fun listSummaries(current: () -> Boolean = { true }): List<AiConversationSummary> = synchronized(lock) {
        checkCurrent(current)
        list().map { AiConversationSummary(it.id, it.title, it.updatedAtEpochMillis) }
            .also { checkCurrent(current) }
    }

    fun find(id: String, current: () -> Boolean = { true }): AiConversation? = synchronized(lock) {
        checkCurrent(current)
        list().firstOrNull { it.id == id }.also { checkCurrent(current) }
    }

    fun upsert(conversation: AiConversation, current: () -> Boolean = { true }) = synchronized(lock) {
        checkCurrent(current)
        val values = list().filterNot { it.id == conversation.id }.plus(conversation)
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(AiLimits.MAX_CONVERSATIONS)
        checkCurrent(current)
        persist(values)
    }

    fun clear() = synchronized(lock) {
        deletionFailed = true
        store.delete(deleteKey = true)
        deletionFailed = false
    }

    fun delete(id: String, current: () -> Boolean = { true }) = synchronized(lock) {
        checkCurrent(current)
        val values = list()
        if (values.none { it.id == id }) return
        checkCurrent(current)
        persist(values.filterNot { it.id == id })
    }

    private fun checkCurrent(current: () -> Boolean) {
        check(current() && !deletionFailed) { "AI operation expired" }
    }

    private fun load(): List<AiConversation> {
        val bytes = store.read() ?: return emptyList()
        return try {
            require(bytes.size <= MAX_STORE_BYTES)
            var encoded = String(bytes, StandardCharsets.UTF_8)
            val root = JSONObject(encoded)
            encoded = ""
            require(root.getInt("format") == 1)
            val array = root.getJSONArray("conversations")
            require(array.length() <= AiLimits.MAX_CONVERSATIONS)
            val conversations = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val messages = item.optJSONArray("messages") ?: JSONArray()
                require(messages.length() <= AiLimits.MAX_HISTORY_MESSAGES)
                AiConversation(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    messages = List(messages.length()) { messageIndex ->
                        val message = messages.getJSONObject(messageIndex)
                        AiMessage(AiRole.valueOf(message.getString("role")), message.getString("content"))
                    },
                    updatedAtEpochMillis = item.optLong("updatedAt", 0L),
                )
            }
            require(conversations.map { it.id }.toSet().size == conversations.size)
            conversations
        } catch (_: Exception) {
            throw IllegalStateException("AI conversations are invalid")
        } finally { bytes.fill(0) }
    }

    private fun persist(values: List<AiConversation>) {
        require(values.size <= AiLimits.MAX_CONVERSATIONS)
        values.forEach { conversation ->
            require(conversation.messages.size <= AiLimits.MAX_HISTORY_MESSAGES)
        }
        val root = JSONObject().apply {
            put("format", 1)
            put("conversations", JSONArray().apply {
                values.forEach { conversation ->
                    put(JSONObject().apply {
                        put("id", conversation.id)
                        put("title", conversation.title)
                        put("updatedAt", conversation.updatedAtEpochMillis)
                        put("messages", JSONArray().apply {
                            conversation.messages.forEach { message ->
                                put(JSONObject().apply {
                                    put("role", message.role.name)
                                    put("content", message.content)
                                })
                            }
                        })
                    })
                }
            })
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            require(bytes.size <= MAX_STORE_BYTES) { "AI conversation storage is full" }
            store.write(bytes)
        } finally { bytes.fill(0) }
    }
    private companion object { const val MAX_STORE_BYTES = 4 * 1024 * 1024 }
}
