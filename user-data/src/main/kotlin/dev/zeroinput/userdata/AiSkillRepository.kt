package dev.zeroinput.userdata

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import dev.zeroinput.ai.api.*
import dev.zeroinput.security.*
import org.json.JSONArray
import org.json.JSONObject

/** Independent versioned encrypted store; no role/conversation format migration. */
class AiSkillRepository(private val store: EncryptedStore) {
    constructor(context: Context) : this(EncryptedFileStore(context, "ai-skills.bin", SecurityAliases.AI_SKILLS))
    private val lock = Any()
    private var deletionFailed = false

    fun read(current: () -> Boolean = { true }): AiSkillLibrary = synchronized(lock) {
        checkCurrent(current)
        val bytes = store.read() ?: return@synchronized AiSkillLibrary().also { checkCurrent(current) }
        try {
            require(bytes.size <= MAX_BYTES)
            withJsonReader(bytes) { reader ->
                var version: Long? = null
                var revision: Long? = null
                var skills: List<AiSkill>? = null
                fields(reader) { key -> when (key) {
                    "format" -> version = number(reader)
                    "revision" -> revision = number(reader)
                    "skills" -> skills = array(reader, 32) { skill(reader) }
                    else -> error("Invalid field")
                } }
                require(version == 1L && reader.peek() == JsonToken.END_DOCUMENT)
                AiSkillLibrary(requireNotNull(revision), requireNotNull(skills)).also { checkCurrent(current) }
            }
        } catch (_: Exception) { throw IllegalStateException("Skill library unavailable") }
        finally { bytes.fill(0) }
    }

    fun write(expectedRevision: Long, value: AiSkillLibrary, current: () -> Boolean): AiSkillLibrary = synchronized(lock) {
        val previous = read(current)
        check(previous.revision == expectedRevision && expectedRevision < Long.MAX_VALUE) { "Skill edit expired" }
        val next = AiSkillLibrary(expectedRevision + 1, value.skills)
        val bytes = JSONObject().put("format", 1).put("revision", next.revision).put("skills", JSONArray(next.skills.map {
            JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
                .put("instructions", it.instructions).put("requiredTools", JSONArray(it.requiredTools))
        })).toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= MAX_BYTES); checkCurrent(current); store.write(bytes) } finally { bytes.fill(0) }
        checkCurrent(current)
        next
    }

    fun clear() = synchronized(lock) { deletionFailed = true; store.delete(deleteKey = true); deletionFailed = false }
    private fun checkCurrent(current: () -> Boolean) { check(!deletionFailed && current()) { "Skill operation expired" } }

    private fun skill(reader: JsonReader): AiSkill {
        val values = mutableMapOf<String, String>()
        var tools: List<String>? = null
        fields(reader) { key -> when (key) {
            "id", "name", "description", "instructions" -> values[key] = text(reader)
            "requiredTools" -> tools = array(reader, 16) { text(reader) }
            else -> error("Invalid field")
        } }
        return AiSkill(values.getValue("id"), values.getValue("name"), values.getValue("description"),
            values.getValue("instructions"), requireNotNull(tools))
    }
    private fun fields(reader: JsonReader, field: (String) -> Unit) {
        val keys = hashSetOf<String>(); reader.beginObject()
        while (reader.hasNext()) { val key = reader.nextName(); require(keys.add(key)); field(key) }
        reader.endObject()
    }
    private fun text(reader: JsonReader): String { require(reader.peek() == JsonToken.STRING); return reader.nextString() }
    private fun number(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER); return reader.nextString().toLongOrNull() ?: error("Invalid number")
    }
    private fun <T> array(reader: JsonReader, limit: Int, item: () -> T): List<T> {
        val values = mutableListOf<T>(); reader.beginArray()
        while (reader.hasNext()) { require(values.size < limit); values += item() }
        reader.endArray(); return values
    }
    private companion object { const val MAX_BYTES = 1_048_576 }
}
