package dev.zeroinput.userdata

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import dev.zeroinput.ai.api.*
import dev.zeroinput.security.*
import org.json.JSONArray
import org.json.JSONObject

/** Separate bounded credential store. Runs only on the serialized AI persistence worker. */
class AiConnectionRepository(private val store: EncryptedStore) {
    constructor(context: Context) : this(EncryptedFileStore(context, "ai-connections.bin", SecurityAliases.AI_CONNECTIONS))
    private val lock = Any()
    private var deletionFailed = false

    fun read(current: () -> Boolean = { true }): AiConnectionLibrary = synchronized(lock) {
        checkCurrent(current)
        val bytes = store.read() ?: return@synchronized AiConnectionLibrary().also { checkCurrent(current) }
        try {
            require(bytes.size <= 131_072)
            withJsonReader(bytes) { reader ->
                var version: Long? = null; var revision: Long? = null; var connections: List<AiConnection>? = null
                fields(reader) { key -> when (key) {
                    "format" -> version = number(reader)
                    "revision" -> revision = number(reader)
                    "connections" -> {
                        val items = mutableListOf<AiConnection>(); reader.beginArray()
                        while (reader.hasNext()) { require(items.size < 8); items += connection(reader) }
                        reader.endArray(); connections = items
                    }
                    else -> error("Invalid field")
                } }
                require(version == 1L && reader.peek() == JsonToken.END_DOCUMENT)
                AiConnectionLibrary(requireNotNull(revision), requireNotNull(connections)).also { checkCurrent(current) }
            }
        } catch (_: Exception) { throw IllegalStateException("Connections unavailable") }
        finally { bytes.fill(0) }
    }
    fun write(expectedRevision: Long, value: AiConnectionLibrary, current: () -> Boolean): AiConnectionLibrary = synchronized(lock) {
        val previous = read(current)
        check(previous.revision == expectedRevision && expectedRevision < Long.MAX_VALUE) { "Connection edit expired" }
        val next = AiConnectionLibrary(expectedRevision + 1, value.connections)
        val bytes = JSONObject().put("format", 1).put("revision", next.revision).put("connections", JSONArray(next.connections.map {
            JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name).put("endpoint", it.endpoint).put("token", it.token)
        })).toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= 131_072); checkCurrent(current); store.write(bytes) } finally { bytes.fill(0) }
        checkCurrent(current); next
    }
    fun clear() = synchronized(lock) { deletionFailed = true; store.delete(deleteKey = true); deletionFailed = false }
    private fun checkCurrent(current: () -> Boolean) { check(!deletionFailed && current()) { "Connection operation expired" } }
    private fun connection(reader: JsonReader): AiConnection {
        val values = mutableMapOf<String, String>()
        fields(reader) { key ->
            require(key in setOf("id", "name", "kind", "endpoint", "token") && reader.peek() == JsonToken.STRING)
            values[key] = reader.nextString()
        }
        return AiConnection(values.getValue("id"), values.getValue("name"), AiConnectionKind.valueOf(values.getValue("kind")),
            values.getValue("endpoint"), values.getValue("token"))
    }
    private fun fields(reader: JsonReader, field: (String) -> Unit) {
        val keys = mutableSetOf<String>(); reader.beginObject()
        while (reader.hasNext()) { val key = reader.nextName(); require(keys.add(key)); field(key) }; reader.endObject()
    }
    private fun number(reader: JsonReader): Long { require(reader.peek() == JsonToken.NUMBER); return reader.nextString().toLong() }
}
