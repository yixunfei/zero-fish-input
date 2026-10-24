package dev.zeroinput.ime.ai

/** One message, sequential bounded blocks, and an explicit successful terminal event. */
internal class AnthropicStreamReader {
    fun read(events: AiEventReader, emit: (String) -> Unit, thinking: (String) -> Unit = {}): String {
        val output = StringBuilder()
        val reasoning = StringBuilder()
        var started = false
        var finished = false
        var block: String? = null
        var index = -1
        while (true) {
            val root = AiProtocolResponse.objectValue(events.next())
            when (root.getString("type")) {
                "ping" -> Unit
                "message_start" -> {
                    requireResponse(!started)
                    val message = root.getJSONObject("message")
                    requireResponse(message.getString("role") == "assistant" && message.getJSONArray("content").length() == 0)
                    started = true
                }
                "content_block_start" -> {
                    requireResponse(started && !finished && block == null && root.getInt("index") == index + 1)
                    index++
                    val content = root.getJSONObject("content_block")
                    block = content.getString("type")
                    requireResponse(block in setOf("text", "thinking", "redacted_thinking"))
                    if (block == "text") {
                        val text = AiProtocolResponse.string(content, "text")
                        AiProtocolResponse.append(output, text); if (text.isNotEmpty()) emit(text)
                    } else if (block == "thinking") {
                        val text = AiProtocolResponse.string(content, "thinking")
                        AiProtocolResponse.append(reasoning, text); if (text.isNotEmpty()) thinking(text)
                    }
                }
                "content_block_delta" -> {
                    requireResponse(block != null && !finished && root.getInt("index") == index)
                    val delta = root.getJSONObject("delta")
                    if (block == "text") {
                        requireResponse(delta.getString("type") == "text_delta")
                        val text = AiProtocolResponse.string(delta, "text")
                        AiProtocolResponse.append(output, text); if (text.isNotEmpty()) emit(text)
                    } else if (block == "thinking" && delta.getString("type") == "thinking_delta") {
                        val text = AiProtocolResponse.string(delta, "thinking")
                        AiProtocolResponse.append(reasoning, text); if (text.isNotEmpty()) thinking(text)
                    } else requireResponse(block in setOf("thinking", "redacted_thinking") &&
                        delta.getString("type") == "signature_delta")
                }
                "content_block_stop" -> {
                    requireResponse(block != null && root.getInt("index") == index)
                    block = null
                }
                "message_delta" -> {
                    requireResponse(started && !finished && block == null)
                    AiProtocolResponse.finishAnthropic(root.getJSONObject("delta").getString("stop_reason"))
                    finished = true
                }
                "message_stop" -> {
                    requireResponse(started && finished && block == null)
                    return AiProtocolResponse.complete(output)
                }
                else -> throw dev.zeroinput.ai.api.AiProviderError.Response("Unexpected AI stream event")
            }
        }
    }
}
