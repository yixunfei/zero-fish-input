package dev.zeroinput.ime.ai

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal

/** Strict bounded JSON and an intentionally explicit JSON Schema subset. */
internal object AiToolJson {
    fun response(reader: java.io.Reader, cancelled: () -> Boolean): JSONObject {
        val text = StringBuilder(); val buffer = CharArray(2_048)
        try {
            while (true) {
                if (cancelled()) throw InterruptedException()
                val count = reader.read(buffer)
                if (count < 0) break
                require(count > 0 && text.length + count <= 262_144)
                text.append(buffer, 0, count)
            }
            if (cancelled()) throw InterruptedException()
            return objectValue(text.toString(), 262_144)
        } finally { buffer.fill('\u0000'); text.setLength(0) }
    }
    fun objectValue(text: String, limit: Int = 32_768): JSONObject {
        require(text.length <= limit)
        return JsonReader(StringReader(text)).use { reader ->
            val value = read(reader, 0) as? JSONObject ?: error("Expected object")
            require(reader.peek() == JsonToken.END_DOCUMENT); value
        }
    }

    private fun read(reader: JsonReader, depth: Int): Any {
        require(depth <= 16)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { result ->
                val keys = mutableSetOf<String>(); reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName(); require(key.length <= 128 && keys.size < 128 && keys.add(key))
                    result.put(key, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { result ->
                reader.beginArray()
                while (reader.hasNext()) { require(result.length() < 128); result.put(read(reader, depth + 1)) }
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString().also { require(it.length <= 65_536 && it.none { c -> c.isISOControl() && c !in "\n\r\t" }) }
            JsonToken.NUMBER -> reader.nextString().let { require(it.length <= 64); BigDecimal(it).also { number -> require(number.abs() <= BigDecimal("1e100")) } }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> error("Invalid JSON")
        }
    }

    fun schema(text: String): JSONObject = objectValue(text, 16_384).also {
        require(it.opt("type") == "object"); checkSchema(it, 0)
    }

    private val common = setOf("type", "title", "description", "enum")
    private fun checkSchema(schema: JSONObject, depth: Int) {
        require(depth <= 8)
        val specific = when (schema.opt("type")) {
            "object" -> setOf("properties", "required", "additionalProperties")
            "array" -> setOf("items", "minItems", "maxItems")
            "string" -> setOf("minLength", "maxLength")
            "integer", "number" -> setOf("minimum", "maximum")
            "boolean" -> emptySet()
            else -> error("Unsupported schema")
        }
        require(schema.keys().asSequence().all { it in common || it in specific })
        for (key in listOf("title", "description")) if (schema.has(key)) require(schema.get(key) is String)
        if (schema.has("enum")) {
            val options = schema.getJSONArray("enum"); require(options.length() in 1..64)
            for (i in 0 until options.length()) require(options.get(i) is String || options.get(i) is Number || options.get(i) is Boolean)
        }
        when (schema.getString("type")) {
            "object" -> {
                if (schema.has("additionalProperties")) require(schema.get("additionalProperties") == false)
                val fields = schema.optJSONObject("properties") ?: JSONObject().also { require(!schema.has("properties")) }
                require(fields.length() <= 64)
                fields.keys().forEach { checkSchema(fields.getJSONObject(it), depth + 1) }
                val required = schema.optJSONArray("required") ?: JSONArray().also { require(!schema.has("required")) }
                val names = (0 until required.length()).map { required.get(it) as? String ?: error("Invalid requirement") }
                require(names.distinct().size == names.size && names.all(fields::has))
            }
            "array" -> { checkSchema(schema.getJSONObject("items"), depth + 1); limits(schema, "minItems", "maxItems", 128) }
            "string" -> limits(schema, "minLength", "maxLength", 32_768)
            "integer", "number" -> {
                for (key in listOf("minimum", "maximum")) if (schema.has(key)) require(schema.get(key) is Number)
                if (schema.has("minimum") && schema.has("maximum")) require(decimal(schema.get("minimum")) <= decimal(schema.get("maximum")))
            }
        }
    }

    private fun limits(schema: JSONObject, low: String, high: String, ceiling: Int) {
        for (key in listOf(low, high)) if (schema.has(key)) {
            val number = decimal(schema.get(key)); require(number >= BigDecimal.ZERO && number <= ceiling.toBigDecimal() && number.stripTrailingZeros().scale() <= 0)
        }
        require(bound(schema, low, 0) <= bound(schema, high, ceiling))
    }
    private fun bound(schema: JSONObject, key: String, fallback: Int) = if (schema.has(key)) decimal(schema.get(key)).intValueExact() else fallback
    private fun decimal(value: Any): BigDecimal { require(value is Number); return BigDecimal(value.toString()) }

    fun arguments(schema: JSONObject, text: String): JSONObject = objectValue(text).also { validate(schema, it) }
    private fun validate(schema: JSONObject, value: Any) {
        if (schema.has("enum")) {
            val options = schema.getJSONArray("enum")
            require((0 until options.length()).any { index ->
                val option = options.get(index)
                if (value is Number && option is Number) decimal(value).compareTo(decimal(option)) == 0 else value == option
            })
        }
        when (schema.getString("type")) {
            "object" -> {
                require(value is JSONObject)
                val fields = schema.optJSONObject("properties") ?: JSONObject()
                val required = schema.optJSONArray("required") ?: JSONArray()
                require((0 until required.length()).all { value.has(required.getString(it)) })
                value.keys().forEach { key -> require(fields.has(key)); validate(fields.getJSONObject(key), value.get(key)) }
            }
            "array" -> {
                require(value is JSONArray && value.length() in bound(schema, "minItems", 0)..bound(schema, "maxItems", 128))
                for (index in 0 until value.length()) validate(schema.getJSONObject("items"), value.get(index))
            }
            "string" -> require(value is String && value.length in bound(schema, "minLength", 0)..bound(schema, "maxLength", 32_768))
            "boolean" -> require(value is Boolean)
            "integer", "number" -> {
                val number = decimal(value)
                if (schema.getString("type") == "integer") require(number.stripTrailingZeros().scale() <= 0)
                if (schema.has("minimum")) require(number >= decimal(schema.get("minimum")))
                if (schema.has("maximum")) require(number <= decimal(schema.get("maximum")))
            }
            else -> error("Unsupported schema")
        }
    }
}
