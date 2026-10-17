package dev.zeroinput.ime.ai

/** Removes inline reasoning blocks while preserving ordinary answer text. */
internal object AiThinkingText {
    data class Split(val answer: String, val thinking: String, val hadThinking: Boolean)

    fun split(value: String): Split {
        val answer = StringBuilder()
        val thinking = StringBuilder()
        var cursor = 0
        var inside = false
        var hadThinking = false
        while (cursor < value.length) {
            if (!inside) {
                val start = value.indexOf("<think>", cursor, ignoreCase = true)
                if (start < 0) {
                    val tail = value.substring(cursor).replace(Regex("</think>", RegexOption.IGNORE_CASE), "")
                    answer.append(tail)
                    break
                }
                answer.append(value, cursor, start)
                cursor = start + 7
                inside = true
                hadThinking = true
            } else {
                val end = value.indexOf("</think>", cursor, ignoreCase = true)
                if (end < 0) {
                    thinking.append(value, cursor, value.length)
                    break
                }
                thinking.append(value, cursor, end)
                cursor = end + 8
                inside = false
            }
        }
        if (value.indexOf("</think>", ignoreCase = true) >= 0) hadThinking = true
        return Split(answer.toString().trim(), thinking.toString().trim(), hadThinking)
    }

    /** Returns an insertable answer. A reasoning-only response fails closed as blank. */
    fun answer(value: String): String = split(value).answer
}
