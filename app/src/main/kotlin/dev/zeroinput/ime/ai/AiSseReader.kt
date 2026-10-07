package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiProviderError
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader

/** Bounded SSE parser; an interrupted or truncated answer is never a completed result. */
internal class AiSseReader {
    fun read(reader: BufferedReader, cancelled: () -> Boolean, emit: (String) -> Unit): String {
        val output = StringBuilder()
        val event = StringBuilder()
        var streamChars = 0
        var finished = false
        while (true) {
            if (cancelled()) throw InterruptedException()
            val line = readLine(reader, cancelled) ?: throw AiProviderError.Response("AI stream ended early")
            streamChars += line.length + 1
            if (streamChars > AiLimits.MAX_STREAM_CHARS) throw AiProviderError.Response("AI stream is too large")
            val field = line.removePrefix("\uFEFF").trimStart(' ', '\t')
            if (field.startsWith("data:")) {
                if (event.length + field.length > AiLimits.MAX_STREAM_LINE_CHARS)
                    throw AiProviderError.Response("AI event is too large")
                event.append(field.substring(5).removePrefix(" ")).append('\n')
            }
            if (field.isNotEmpty() || event.isEmpty()) continue
            val data = event.toString().trim()
            event.setLength(0)
            if (data == "[DONE]") {
                if (!finished || output.isBlank()) throw AiProviderError.Response("AI stream ended early")
                return output.toString()
            }
            if (data.isEmpty()) continue
            val chunk = parse(data)
            if (chunk == null) continue // Optional usage-only event after the text choice.
            if (finished) throw AiProviderError.Response("AI content followed the completed choice")
            finished = chunk.finished
            if (output.length + chunk.text.length > AiLimits.MAX_OUTPUT_CHARS) {
                throw AiProviderError.Response("AI response is too large")
            }
            if (chunk.text.isNotEmpty()) { output.append(chunk.text); emit(chunk.text) }
        }
    }

    private data class Chunk(val text: String, val finished: Boolean)

    private fun parse(data: String): Chunk? = try {
        val tokens = JSONTokener(data)
        val root = tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException()
        require(tokens.nextClean() == '\u0000' && !root.has("error"))
        val choices = root.getJSONArray("choices")
        if (choices.length() == 0) null else {
            require(choices.length() == 1)
            val choice = choices.getJSONObject(0)
            if (choice.has("index")) require(choice.get("index") == 0)
            val finished = !choice.isNull("finish_reason")
            if (finished) {
                require(choice.get("finish_reason") == "stop")
            }
            val delta = choice.getJSONObject("delta")
            AiResponseReader.requireTextOnly(delta)
            val text = if (delta.isNull("content")) "" else {
                // Android JSONObject.getString coerces non-string values into text.
                delta.get("content") as? String ?: throw IllegalArgumentException()
            }
            Chunk(text, finished)
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
