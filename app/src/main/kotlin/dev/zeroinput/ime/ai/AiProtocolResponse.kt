package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.json.JSONObject
import java.io.BufferedReader

internal object AiProtocolResponse {
    fun read(protocol: AiProtocol, json: Boolean, reader: BufferedReader,
        cancelled: () -> Boolean, emit: (String) -> Unit = {},
        thinking: (String) -> Unit = {}): String = when (protocol) {
        AiProtocol.CHAT_COMPLETIONS -> if (json) AiResponseReader.chat(reader, cancelled, thinking)
            else AiSseReader().read(reader, cancelled, emit, thinking)
        AiProtocol.RESPONSES -> if (json) AiResponseReader.parse(reader, cancelled,
            AiLimits.MAX_STREAM_CHARS) { responses(it, thinking) }
            else responsesStream(AiEventReader(reader, cancelled), emit, thinking)
        AiProtocol.ANTHROPIC_MESSAGES -> if (json) AiResponseReader.parse(reader, cancelled,
            AiLimits.MAX_STREAM_CHARS) { anthropic(it, thinking) }
            else AnthropicStreamReader().read(AiEventReader(reader, cancelled), emit, thinking)
    }

    internal fun objectValue(text: String): JSONObject = AiJsonResponse.objectValue(text)

    private fun responses(root: JSONObject, thinking: (String) -> Unit = {}): String {
        if (root.optString("status") == "incomplete") throw outputLimit()
        requireResponse(root.optString("status") == "completed" && root.isNull("error"))
        val output = root.getJSONArray("output")
        val text = StringBuilder()
        for (index in 0 until output.length()) {
            val item = output.getJSONObject(index)
            when (item.getString("type")) {
                "reasoning" -> {
                    val summary = item.optJSONArray("summary")
                    if (summary != null) for (part in 0 until summary.length()) {
                        val block = summary.getJSONObject(part)
                        requireResponse(block.getString("type") == "summary_text")
                        thinking(string(block, "text"))
                    }
                }
                "message" -> {
                    requireResponse(item.getString("role") == "assistant" && item.optString("status", "completed") == "completed")
                    val content = item.getJSONArray("content")
                    for (part in 0 until content.length()) {
                        val block = content.getJSONObject(part)
                        requireResponse(block.getString("type") == "output_text")
                        append(text, string(block, "text"))
                    }
                }
                else -> throw AiProviderError.Response("Non-text AI output is unsupported")
            }
        }
        return complete(text)
    }

    private fun responsesStream(events: AiEventReader, emit: (String) -> Unit, thinking: (String) -> Unit): String {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        while (true) {
            val root = objectValue(events.next())
            when (root.getString("type")) {
                "response.output_text.delta" -> {
                    val delta = string(root, "delta")
                    append(text, delta); if (delta.isNotEmpty()) emit(delta)
                }
                "response.reasoning_summary_text.delta" -> {
                    val delta = string(root, "delta")
                    append(reasoning, delta); if (delta.isNotEmpty()) thinking(delta)
                }
                "response.completed" -> {
                    val finalReasoning = StringBuilder()
                    val final = responses(root.getJSONObject("response")) { append(finalReasoning, it) }
                    requireResponse(text.isEmpty() || text.toString() == final)
                    if (reasoning.isEmpty() && finalReasoning.isNotEmpty()) thinking(finalReasoning.toString())
                    return final
                }
                "response.incomplete" -> throw outputLimit()
                "error", "response.failed" -> throw AiProviderError.Response("AI generation failed")
            }
        }
    }

    private fun anthropic(root: JSONObject, thinking: (String) -> Unit): String {
        requireResponse(root.getString("type") == "message" && root.getString("role") == "assistant")
        finishAnthropic(root.optString("stop_reason"))
        val content = root.getJSONArray("content")
        val text = StringBuilder()
        for (index in 0 until content.length()) {
            val part = content.getJSONObject(index)
            when (part.getString("type")) {
                "text" -> append(text, string(part, "text"))
                "thinking" -> thinking(string(part, "thinking"))
                "redacted_thinking" -> Unit
                else -> throw AiProviderError.Response("Non-text AI output is unsupported")
            }
        }
        return complete(text)
    }

    internal fun finishAnthropic(reason: String) {
        if (reason == "max_tokens") throw outputLimit()
        requireResponse(reason in setOf("end_turn", "stop_sequence"))
    }

    internal fun string(root: JSONObject, key: String): String =
        root.get(key) as? String ?: throw AiProviderError.Response("AI response is invalid")

    internal fun append(output: StringBuilder, delta: String) {
        requireResponse(output.length + delta.length <= AiLimits.MAX_OUTPUT_CHARS)
        output.append(delta)
    }

    internal fun complete(output: StringBuilder): String {
        if (output.isBlank()) throw AiProviderError.Response("AI response is empty", AiResponseFailure.EMPTY)
        return output.toString()
    }

    internal fun outputLimit() = AiProviderError.Response("AI output token limit reached", AiResponseFailure.OUTPUT_LIMIT)
}
