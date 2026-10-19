package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiProviderError
import org.json.JSONObject
import org.json.JSONTokener

/** Checks nesting before handing untrusted JSON to the platform's recursive parser. */
internal object AiJsonResponse {
    private const val MAX_DEPTH = 32

    fun objectValue(text: String): JSONObject {
        try {
            checkNesting(text)
            val tokens = JSONTokener(text)
            val root = tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException()
            require(tokens.nextClean() == '\u0000' && root.isNull("error"))
            return root
        } catch (_: Exception) {
            throw AiProviderError.Response("AI response is invalid")
        }
    }

    private fun checkNesting(text: String) {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (character in text) {
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
            } else when (character) {
                '"' -> quote = character
                // Platform JSON also accepts comments and single quotes. Reject those
                // extensions so they cannot hide delimiters from this depth check.
                '\'', '/', '#' -> throw IllegalArgumentException()
                '{', '[' -> require(++depth <= MAX_DEPTH)
                '}', ']' -> require(--depth >= 0)
            }
        }
        require(depth == 0 && quote == null)
    }
}
