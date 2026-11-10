package dev.zeroinput.userdata

import android.util.JsonReader
import android.util.JsonToken
import dev.zeroinput.ai.api.*
import org.json.JSONArray
import org.json.JSONObject

internal object AiContextLibraryFormat {
    private const val MAX_BYTES = 1_048_576

    fun encode(value: AiContextLibrary): ByteArray = JSONObject().apply {
        put("format", 1); put("revision", value.revision)
        put("personas", JSONArray(value.personas.map {
            JSONObject().put("id", it.id).put("name", it.name).put("description", it.description).put("instructions", it.instructions)
        }))
        put("memories", JSONArray(value.memories.map {
            JSONObject().put("id", it.id).put("personaId", it.personaId ?: JSONObject.NULL).put("text", it.text)
        }))
    }.toString().toByteArray(Charsets.UTF_8).also {
        if (it.size > MAX_BYTES) { it.fill(0); error("Context storage is full") }
    }

    fun decode(bytes: ByteArray): AiContextLibrary = try {
        require(bytes.size <= MAX_BYTES)
        withJsonReader(bytes) { reader ->
            var revision: Long? = null
            var format: Long? = null
            var personas: List<AiPersona>? = null
            var memories: List<AiMemory>? = null
            fields(reader) { key -> when (key) {
                "format" -> format = number(reader)
                "revision" -> revision = number(reader)
                "personas" -> personas = array(reader, 32) { persona(reader) }
                "memories" -> memories = array(reader, 128) { memory(reader) }
                else -> error("Invalid field")
            } }
            require(format == 1L && reader.peek() == JsonToken.END_DOCUMENT)
            AiContextLibrary(requireNotNull(revision), requireNotNull(personas), requireNotNull(memories))
        }
    } catch (_: Exception) { throw IllegalStateException("Context library is invalid") }

    private fun persona(reader: JsonReader): AiPersona {
        val values = mutableMapOf<String, String>()
        fields(reader) { key ->
            require(key in setOf("id", "name", "description", "instructions"))
            values[key] = text(reader)
        }
        return AiPersona(values.getValue("id"), values.getValue("name"), values.getValue("description"), values.getValue("instructions"))
    }

    private fun memory(reader: JsonReader): AiMemory {
        var id: String? = null
        var scope: String? = null
        var body: String? = null
        var scopeSeen = false
        fields(reader) { key -> when (key) {
            "id" -> id = text(reader)
            "personaId" -> {
                scopeSeen = true
                scope = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else text(reader)
            }
            "text" -> body = text(reader)
            else -> error("Invalid field")
        } }
        require(scopeSeen)
        return AiMemory(requireNotNull(id), scope, requireNotNull(body))
    }

    private fun fields(reader: JsonReader, field: (String) -> Unit) {
        val keys = hashSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) { val key = reader.nextName(); require(keys.add(key)); field(key) }
        reader.endObject()
    }

    private fun text(reader: JsonReader): String { require(reader.peek() == JsonToken.STRING); return reader.nextString() }
    private fun number(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        return reader.nextString().toLongOrNull() ?: error("Invalid number")
    }
    private fun <T> array(reader: JsonReader, limit: Int, item: () -> T): List<T> {
        val values = mutableListOf<T>()
        reader.beginArray()
        while (reader.hasNext()) { require(values.size < limit); values += item() }
        reader.endArray()
        return values
    }
}
