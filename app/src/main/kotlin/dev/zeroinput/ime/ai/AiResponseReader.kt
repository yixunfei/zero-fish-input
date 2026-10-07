package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiProviderError
import org.json.JSONObject
import org.json.JSONTokener
import java.io.Reader

/** Bounded JSON responses for servers returning non-streaming completions and model catalogs. */
internal object AiResponseReader {
    fun models(reader: Reader, cancelled: () -> Boolean): List<String> = parse(reader, cancelled, 512 * 1024) { root ->
        val data = root.getJSONArray("data")
        require(data.length() in 1..2048)
        val ids = LinkedHashSet<String>()
        repeat(data.length()) { index ->
            val id = data.getJSONObject(index).get("id") as? String ?: throw IllegalArgumentException()
            require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl) && id == id.trim())
            ids += id
        }
        ids.toList().sorted()
    }

    fun chat(reader: Reader, cancelled: () -> Boolean): String = parse(reader, cancelled, AiLimits.MAX_STREAM_CHARS) { root ->
        val choices = root.getJSONArray("choices")
        require(choices.length() == 1)
        val choice = choices.getJSONObject(0)
        require(choice.get("finish_reason") == "stop")
        val message = choice.getJSONObject("message")
        requireTextOnly(message)
        val content = message.get("content") as? String ?: throw IllegalArgumentException()
        require(content.isNotBlank() && content.length <= AiLimits.MAX_OUTPUT_CHARS)
        content
    }

    internal fun requireTextOnly(message: JSONObject) {
        require(message.isNull("function_call"))
        require(message.isNull("tool_calls") || message.optJSONArray("tool_calls")?.length() == 0)
    }

    private fun <T> parse(reader: Reader, cancelled: () -> Boolean, limit: Int, decode: (JSONObject) -> T): T {
        val text = StringBuilder()
        val buffer = CharArray(2048)
        try {
            while (true) {
                if (cancelled()) throw InterruptedException()
                val count = reader.read(buffer)
                if (count < 0) break
                if (text.length + count > limit) throw AiProviderError.Response("AI response is too large")
                text.append(buffer, 0, count)
            }
            if (cancelled()) throw InterruptedException()
            return try {
                val tokens = JSONTokener(text.toString())
                val root = tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException()
                require(tokens.nextClean() == '\u0000' && !root.has("error"))
                decode(root)
            } catch (_: Exception) { throw AiProviderError.Response("AI response is invalid") }
        } finally { buffer.fill('\u0000'); text.setLength(0) }
    }
}
