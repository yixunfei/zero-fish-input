package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiProviderError
import org.json.JSONObject
import java.io.BufferedReader

/** Bounded SSE parser; an interrupted or truncated answer is never a completed result. */
internal class AiSseReader {
    fun read(reader: BufferedReader, cancelled: () -> Boolean, emit: (String) -> Unit): String {
        val output = StringBuilder()
        var streamChars = 0
        while (true) {
            if (cancelled()) throw InterruptedException()
            val line = readLine(reader, cancelled) ?: throw AiProviderError.Response("AI stream ended early")
            streamChars += line.length + 1
            if (streamChars > AiLimits.MAX_STREAM_CHARS) throw AiProviderError.Response("AI stream is too large")
            val field = line.removePrefix("\uFEFF").trimStart(' ', '\t')
            if (!field.startsWith("data:")) continue
            val data = field.substring(5).trim()
            if (data == "[DONE]") return output.toString()
            if (data.isEmpty()) continue
            val delta = parse(data)
            if (output.length + delta.length > AiLimits.MAX_OUTPUT_CHARS) {
                throw AiProviderError.Response("AI response is too large")
            }
            if (delta.isNotEmpty()) { output.append(delta); emit(delta) }
        }
    }

    private fun parse(data: String): String = try {
        val root = JSONObject(data)
        if (root.has("error")) throw IllegalArgumentException()
        val choices = root.getJSONArray("choices")
        if (choices.length() == 0) "" else {
            val choice = choices.getJSONObject(0)
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                // DONE terminates the transport even when the model answer was truncated.
                require(choice.get("finish_reason") == "stop")
            }
            val delta = choice.getJSONObject("delta")
            if (!delta.has("content") || delta.isNull("content")) "" else {
                // Android JSONObject.getString coerces non-string values into text.
                delta.get("content") as? String ?: throw IllegalArgumentException()
            }
        }
    } catch (_: Exception) { throw AiProviderError.Response("AI response is invalid") }

    private fun readLine(reader: BufferedReader, cancelled: () -> Boolean): String? {
        val line = StringBuilder()
        while (true) {
            if (cancelled()) throw InterruptedException()
            val value = reader.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= AiLimits.MAX_STREAM_LINE_CHARS) throw AiProviderError.Response("AI stream line is too large")
            line.append(value.toChar())
        }
    }
}
