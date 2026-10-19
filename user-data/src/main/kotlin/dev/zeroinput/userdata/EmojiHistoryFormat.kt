package dev.zeroinput.userdata

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject

internal data class EmojiUsage(val value: String, val count: Int, val lastUsed: Long)

internal object EmojiHistoryFormat {
    const val MAX_ENTRIES = 128
    const val MAX_COUNT = 1_000_000
    private const val MAX_BYTES = 128 * 1024

    // JSONObject serialization briefly creates a non-wipeable String; it is not retained.
    fun encode(values: List<EmojiUsage>): ByteArray = JSONObject().apply {
        put("format", 1)
        put("entries", JSONArray().apply {
            values.forEach { entry ->
                put(JSONObject().apply {
                    put("value", entry.value)
                    put("count", entry.count)
                    put("lastUsed", entry.lastUsed)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): List<EmojiUsage> {
        if (bytes.size > MAX_BYTES) corrupt()
        return try {
            withJsonReader(bytes, ::readRoot)
        } catch (_: Exception) { corrupt() }
    }

    private fun readRoot(input: JsonReader): List<EmojiUsage> {
        var entries: List<EmojiUsage>? = null
        var version: Long? = null
        val seen = mutableSetOf<String>()
        input.beginObject()
        while (input.hasNext()) {
            val field = input.nextName()
            if (!seen.add(field)) corrupt()
            when (field) {
                "format" -> version = number(input)
                "entries" -> entries = readEntries(input)
                else -> corrupt()
            }
        }
        input.endObject()
        if (version != 1L || entries == null || input.peek() != JsonToken.END_DOCUMENT) corrupt()
        return entries
    }

    private fun readEntries(input: JsonReader): List<EmojiUsage> {
        val entries = mutableListOf<EmojiUsage>()
        val values = mutableSetOf<String>()
        input.beginArray()
        while (input.hasNext()) {
            if (entries.size >= MAX_ENTRIES) corrupt()
            val entry = readEntry(input)
            if (!values.add(entry.value)) corrupt()
            entries += entry
        }
        input.endArray()
        return entries
    }

    private fun readEntry(input: JsonReader): EmojiUsage {
        var value: String? = null
        var count: Long? = null
        var last: Long? = null
        val seen = mutableSetOf<String>()
        input.beginObject()
        while (input.hasNext()) {
            val field = input.nextName()
            if (!seen.add(field)) corrupt()
            when (field) {
                "value" -> {
                    if (input.peek() != JsonToken.STRING) corrupt()
                    value = input.nextString()
                }
                "count" -> count = number(input)
                "lastUsed" -> last = number(input)
                else -> corrupt()
            }
        }
        input.endObject()
        if (value == null || !ExpressionLimits.validText(value) || count == null || count !in 1..MAX_COUNT ||
            last == null || last !in 0 until Long.MAX_VALUE) corrupt()
        return EmojiUsage(value, count.toInt(), last)
    }

    private fun number(input: JsonReader): Long {
        if (input.peek() != JsonToken.NUMBER) corrupt()
        return input.nextString().toLongOrNull() ?: corrupt()
    }

    private fun corrupt(): Nothing = throw ExpressionException(ExpressionFailure.CORRUPT)
}
