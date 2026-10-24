package dev.zeroinput.userdata

import android.util.JsonReader
import android.util.JsonToken
import dev.zeroinput.engine.api.InputLanguage
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object UserLexiconFormat {
    const val MAX_TERMS = 20_000
    const val MAX_FREQUENCY = 1_000_000
    const val MAX_SHORTCUT_LENGTH = 64
    const val MAX_VALUE_LENGTH = 128
    const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
    private const val FORMAT_VERSION = 1
    private val idPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun parse(bytes: ByteArray): List<UserTerm> {
        try {
            return withJsonReader(bytes, ::readDocument)
        } catch (error: InterruptedIOException) {
            throw error
        } catch (_: IOException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: IllegalStateException) {
            invalid()
        }
    }

    private fun readDocument(reader: JsonReader): List<UserTerm> {
        val fields = mutableSetOf<String>()
        var terms: List<UserTerm>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field))
            when (field) {
                "format" -> require(readLong(reader) == FORMAT_VERSION.toLong())
                "terms" -> terms = readTerms(reader)
                else -> invalid()
            }
        }
        reader.endObject()
        require("format" in fields && reader.peek() == JsonToken.END_DOCUMENT)
        return terms ?: invalid()
    }

    private fun readTerms(reader: JsonReader): List<UserTerm> {
        val terms = ArrayList<UserTerm>()
        val ids = HashSet<String>()
        val identities = HashSet<PhraseIdentity>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(terms.size < MAX_TERMS)
            val term = readTerm(reader)
            require(ids.add(term.id) && identities.add(identity(term)))
            terms += term
        }
        reader.endArray()
        return terms
    }

    private fun readTerm(reader: JsonReader): UserTerm {
        val fields = mutableSetOf<String>()
        var id: String? = null
        var shortcut: String? = null
        var value: String? = null
        var language: InputLanguage? = null
        var frequency = 1L
        var lastUsed = 0L
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field))
            when (field) {
                "id" -> id = readString(reader).also { require(idPattern.matches(it)) }.lowercase(Locale.ROOT)
                "shortcut" -> shortcut = validateShortcut(readString(reader))
                "value" -> value = validateValue(readString(reader))
                "language" -> language = InputLanguage.valueOf(readString(reader))
                "frequency" -> frequency = readLong(reader).also { require(it in 1..MAX_FREQUENCY) }
                "lastUsed" -> lastUsed = readLong(reader).also { require(it >= 0) }
                else -> invalid()
            }
        }
        reader.endObject()
        return UserTerm(id ?: UUID.randomUUID().toString(), shortcut ?: invalid(), value ?: invalid(),
            language ?: invalid(), frequency.toInt(), lastUsed)
    }

    private fun readString(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING)
        return reader.nextString()
    }

    private fun readLong(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        return reader.nextString().toLong()
    }

    // Callers convert this JSON model directly to owned bytes. The serializer's
    // temporary String is not retained; token Strings also remain JVM-managed.
    fun serialize(values: List<UserTerm>): JSONObject = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("terms", JSONArray().apply {
            values.forEach { term ->
                put(JSONObject().apply {
                    put("id", term.id)
                    put("shortcut", term.shortcut)
                    put("value", term.value)
                    put("language", term.language.name)
                    put("frequency", term.frequency)
                    put("lastUsed", term.lastUsedEpochMillis)
                })
            }
        })
    }

    fun validateShortcut(value: String): String = validateText(value, MAX_SHORTCUT_LENGTH)
    fun validateValue(value: String): String = validateText(value, MAX_VALUE_LENGTH)

    private fun validateText(value: String, maxLength: Int): String {
        require(value.length <= maxLength && value.none(Char::isISOControl))
        require(StandardCharsets.UTF_8.newEncoder().canEncode(value))
        return value.trim().also { require(it.isNotEmpty()) }
    }

    fun identity(term: UserTerm) = PhraseIdentity(term.shortcut.lowercase(Locale.ROOT), term.value, term.language)

    private fun invalid(): Nothing = throw UserDictionaryException(UserDictionaryFailure.INVALID_FORMAT)

    data class PhraseIdentity(val shortcut: String, val value: String, val language: InputLanguage)
}
