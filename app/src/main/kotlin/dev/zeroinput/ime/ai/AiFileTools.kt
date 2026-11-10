package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.json.JSONArray
import org.json.JSONObject

internal object AiFileTools {
    const val READ = "files_read"
    const val SEARCH = "files_search"
    const val SAVE = "files_save_as"
    fun definitions(fileIds: List<String>): List<AiToolDefinition> = buildList {
        require(fileIds.size <= 8 && fileIds.distinct().size == fileIds.size && fileIds.all { it.matches(Regex("file[1-8]")) })
        if (fileIds.isNotEmpty()) {
            val file = JSONObject().put("type", "string").put("enum", JSONArray(fileIds))
            add(AiToolDefinition("files/read", READ, "Read selected file", "Read a bounded excerpt of an explicitly selected UTF-8 document. Offsets count UTF-16 characters.",
                schema(JSONObject().put("file", file).put("offset", integer(0, 65_536)).put("length", integer(1, 3_500)), "file", "offset", "length"), AiToolOrigin.FILE, AiToolEffect.READ))
            add(AiToolDefinition("files/search", SEARCH, "Search selected file", "Find literal text in an explicitly selected UTF-8 document. Returns bounded excerpts, not a full file.",
                schema(JSONObject().put("file", file).put("query", string(256)).put("ignore_case", JSONObject().put("type", "boolean")), "file", "query", "ignore_case"), AiToolOrigin.FILE, AiToolEffect.READ))
        }
        add(AiToolDefinition("files/save-as", SAVE, "Save new document", "Ask the user to create and confirm a new text document. Never overwrite an existing document or retry an uncertain write.",
            schema(JSONObject().put("name", string(128)).put("content", string(16_384)), "name", "content"), AiToolOrigin.FILE, AiToolEffect.WRITE))
    }
    private fun integer(min: Int, max: Int) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
    private fun string(max: Int) = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", max)
    private fun schema(properties: JSONObject, vararg required: String) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required.toList())).put("additionalProperties", false).toString()

    fun excerpt(text: String, offset: Int, length: Int): String {
        require(offset in 0..text.length && length in 1..3_500)
        val end = minOf(text.length, offset + length)
        return "Characters $offset..$end of ${text.length}:\n" + text.substring(offset, end)
    }
    fun search(text: String, query: String, ignoreCase: Boolean): String {
        require(query.isNotBlank() && query.length <= 256 && text.length <= 65_536)
        val output = StringBuilder(); var from = 0; var matches = 0
        while (from < text.length && matches < 10 && output.length < 3_000) {
            val index = text.indexOf(query, from, ignoreCase)
            if (index < 0) break
            val start = (index - 80).coerceAtLeast(0); val end = (index + query.length + 80).coerceAtMost(text.length)
            val excerpt = "[$index] ${text.substring(start, end)}\n"
            if (output.length + excerpt.length > 3_500) break
            output.append(excerpt); matches++; from = index + query.length
        }
        return if (matches == 0) "No literal matches." else "Matches shown: $matches (bounded preview).\n$output"
    }
    fun mimeForName(name: String): String {
        require(name.isNotBlank() && name.length <= 128 && name == name.trim() && name !in setOf(".", "..") &&
            name.none { it.isISOControl() || it in "/\\:*?\"<>|" })
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> error("Unsupported output name")
        }
    }
}
