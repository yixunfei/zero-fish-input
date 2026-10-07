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
            withJsonReader(bytes) { reader ->
                var format: Long? = null
                var conversations: List<AiConversation>? = null
                val fields = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    require(fields.add(field))
                    when (field) {
                        "format" -> format = nextLong(reader)
                        "conversations" -> conversations = readConversations(reader)
                        else -> invalid()
                    }
                }
                reader.endObject()
                require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
                require(format == 1L && conversations != null)
                require(conversations.map { it.id }.toSet().size == conversations.size)
                conversations
            }
        } catch (_: Exception) {
            throw IllegalStateException("AI conversations are invalid")
        } finally { bytes.fill(0) }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid")

    private fun nextString(reader: android.util.JsonReader): String {
        require(reader.peek() == android.util.JsonToken.STRING)
        return reader.nextString()
    }

    private fun nextLong(reader: android.util.JsonReader): Long {
        require(reader.peek() == android.util.JsonToken.NUMBER)
        return reader.nextString().toLongOrNull() ?: invalid()
    }

    private fun readConversations(reader: android.util.JsonReader): List<AiConversation> {
        val conversations = ArrayList<AiConversation>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(conversations.size < AiLimits.MAX_CONVERSATIONS)
            var id: String? = null
            var title: String? = null
            var messages: List<AiMessage>? = null
            var updatedAt = 0L
            val fields = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field))
                when (field) {
                    "id" -> id = nextString(reader)
                    "title" -> title = nextString(reader)
                    "messages" -> messages = readMessages(reader)
                    "updatedAt" -> updatedAt = nextLong(reader)
                    else -> invalid()
                }
            }
            reader.endObject()
            conversations += AiConversation(
                id = id ?: invalid(),
                title = title ?: invalid(),
                messages = messages ?: invalid(),
                updatedAtEpochMillis = updatedAt,
            )
        }
        reader.endArray()
        return conversations
    }

    private fun readMessages(reader: android.util.JsonReader): List<AiMessage> {
        val messages = ArrayList<AiMessage>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(messages.size < AiLimits.MAX_HISTORY_MESSAGES)
            var role: String? = null
            var content: String? = null
            val fields = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field))
                when (field) {
                    "role" -> role = nextString(reader)
                    "content" -> content = nextString(reader)
                    else -> invalid()
                }
            }
            reader.endObject()
            messages += AiMessage(AiRole.valueOf(role ?: invalid()), content ?: invalid())
        }
        reader.endArray()
        return messages
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
