package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiProviderError
import java.io.BufferedReader

/** Bounded SSE framing shared by protocols whose terminal event is not [DONE]. */
internal class AiEventReader(private val reader: BufferedReader, private val cancelled: () -> Boolean) {
    private var total = 0
    private var first = true
    private var skipLf = false

    fun next(): String {
        val data = StringBuilder()
        while (true) {
            val line = line() ?: throw AiProviderError.Response("AI stream ended early")
            val field = if (first) line.removePrefix("\uFEFF") else line
            first = false
            if (field.isEmpty() && data.isNotEmpty()) return data.toString().trimEnd('\n')
            if (field.startsWith("data:")) {
                val part = field.substring(5).removePrefix(" ")
                requireResponse(data.length + part.length + 1 <= AiLimits.MAX_STREAM_LINE_CHARS)
                data.append(part).append('\n')
            }
        }
    }

    private fun line(): String? {
        val value = StringBuilder()
        while (true) {
            if (cancelled()) throw InterruptedException()
            val char = reader.read()
            if (char < 0) return if (value.isEmpty()) null else value.toString()
            if (skipLf) {
                skipLf = false
                if (char == '\n'.code) {
                    requireResponse(++total <= AiLimits.MAX_STREAM_CHARS)
                    continue
                }
            }
            requireResponse(++total <= AiLimits.MAX_STREAM_CHARS)
            if (char == '\r'.code) {
                skipLf = true
                return value.toString()
            }
            if (char == '\n'.code) return value.toString().removeSuffix("\r")
            requireResponse(value.length < AiLimits.MAX_STREAM_LINE_CHARS)
            value.append(char.toChar())
        }
    }
}

internal fun requireResponse(valid: Boolean) {
    if (!valid) throw AiProviderError.Response("AI response is invalid")
}
