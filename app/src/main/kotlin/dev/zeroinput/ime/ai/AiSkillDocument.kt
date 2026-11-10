package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiSkill
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONTokener
import java.io.StringReader

/** Deliberately small front-matter grammar, not an arbitrary YAML interpreter. */
internal object AiSkillDocument {
    class Draft(val name: String, val description: String, val instructions: String, val tools: List<String>) {
        override fun toString() = "SkillDraft(redacted)"
    }

    fun parse(text: String): Draft {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= AiSkill.MAX_BYTES) } finally { bytes.fill(0) }
        require(text.none { it.isISOControl() && it !in "\n\r\t" })
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull() != "---") return Draft("", "", text.trim(), emptyList()).also { require(it.instructions.isNotBlank()) }
        val end = (1 until minOf(lines.size, 65)).firstOrNull { lines[it] == "---" } ?: error("Unsupported Skill metadata")
        val fields = mutableMapOf<String, String>()
        for (line in lines.subList(1, end)) {
            if (line.isBlank()) continue
            require(!line.first().isWhitespace())
            val delimiter = line.indexOf(':'); require(delimiter > 0)
            val key = line.substring(0, delimiter)
            require(key in setOf("name", "description", "allowed-tools") && key !in fields)
            fields[key] = line.substring(delimiter + 1).trim()
        }
        val tools = fields["allowed-tools"]?.let { value ->
            JsonReader(StringReader(value)).use { reader ->
                val names = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    require(names.size < 16 && reader.peek() == JsonToken.STRING)
                    names += reader.nextString()
                }
                reader.endArray(); require(reader.peek() == JsonToken.END_DOCUMENT)
                names
            }
        }.orEmpty()
        fun scalar(key: String): String {
            val value = fields[key].orEmpty()
            if (value.startsWith('"') || value.startsWith('\'')) {
                val tokener = JSONTokener(value)
                val decoded = tokener.nextValue() as? String ?: error("Invalid Skill scalar")
                require(tokener.nextClean() == '\u0000')
                return decoded
            }
            require(value.firstOrNull() !in listOf('|', '>', '&', '*', '!', '{', '[') && !value.contains(" #"))
            return value
        }
        val name = scalar("name"); val description = scalar("description")
        val body = lines.drop(end + 1).joinToString("\n").trim()
        AiSkill("validation", name, description, body, tools)
        return Draft(name, description, body, tools)
    }
}
