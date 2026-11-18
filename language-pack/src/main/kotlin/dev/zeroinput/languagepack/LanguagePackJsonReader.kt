package dev.zeroinput.languagepack

import java.io.Reader
import java.util.concurrent.CancellationException

/** Strict JSON events shared by manifest validation and streaming dictionary loading. */
internal class LanguagePackJsonReader(private val source: Reader, private val visitor: Visitor = Visitor.None) {
    interface Visitor {
        fun beginObject() = Unit
        fun name(value: String) = Unit
        fun endObject() = Unit
        fun beginArray() = Unit
        fun endArray() = Unit
        fun scalar(value: String, quoted: Boolean) = Unit
        object None : Visitor
    }

    private var next = source.read()
    private var activeKeys = 0

    fun read() {
        if (next == 0xFEFF) advance()
        value(0)
        whitespace()
        require(next == -1) { "Language pack JSON has trailing data" }
    }

    private fun value(depth: Int) {
        whitespace()
        when (next) {
            '{'.code -> objectValue(depth + 1)
            '['.code -> arrayValue(depth + 1)
            '"'.code -> visitor.scalar(string(), true)
            't'.code, 'f'.code, 'n'.code -> token(literal = true)
            '-'.code, in '0'.code..'9'.code -> token(literal = false)
            else -> throw IllegalArgumentException("Invalid language pack JSON token")
        }
    }

    private fun objectValue(depth: Int) {
        require(depth <= MAX_DEPTH) { "Language pack JSON is too deeply nested" }
        advance()
        visitor.beginObject()
        val keys = HashSet<String>()
        try {
            whitespace()
            if (!consume('}')) while (true) {
                whitespace()
                require(next == '"'.code) { "Object keys must be quoted" }
                val key = string()
                require(keys.add(key)) { "Duplicate JSON object key" }
                activeKeys++
                require(keys.size <= MAX_OBJECT_KEYS && activeKeys <= MAX_ACTIVE_KEYS) { "Too many JSON object keys" }
                visitor.name(key)
                whitespace()
                require(consume(':')) { "Missing JSON object separator" }
                value(depth)
                whitespace()
                if (consume('}')) break
                require(consume(',')) { "Missing JSON object delimiter" }
                whitespace()
                require(next != '}'.code) { "Trailing JSON object delimiter" }
            }
        } finally { activeKeys -= keys.size }
        visitor.endObject()
    }

    private fun arrayValue(depth: Int) {
        require(depth <= MAX_DEPTH) { "Language pack JSON is too deeply nested" }
        advance()
        visitor.beginArray()
        whitespace()
        if (!consume(']')) while (true) {
            value(depth)
            whitespace()
            if (consume(']')) break
            require(consume(',')) { "Missing JSON array delimiter" }
            whitespace()
            require(next != ']'.code) { "Trailing JSON array delimiter" }
        }
        visitor.endArray()
    }

    private fun string(): String {
        require(consume('"')) { "JSON string must start with a quote" }
        val output = StringBuilder()
        while (next >= 0) {
            val character = next.toChar()
            advance()
            when {
                character == '"' -> return output.toString()
                character < ' ' -> throw IllegalArgumentException("Invalid language pack JSON string")
                character == '\\' -> output.append(escape())
                else -> output.append(character)
            }
            require(output.length <= MAX_TOKEN_CHARS) { "Language pack JSON token is too long" }
        }
        throw IllegalArgumentException("Incomplete language pack JSON string")
    }

    private fun escape(): Char {
        val escaped = next
        advance()
        return when (escaped) {
            '"'.code, '\\'.code, '/'.code -> escaped.toChar()
            'b'.code -> '\b'
            'f'.code -> '\u000C'
            'n'.code -> '\n'
            'r'.code -> '\r'
            't'.code -> '\t'
            'u'.code -> {
                var code = 0
                repeat(4) {
                    require(next >= 0 && next.toChar() in "0123456789abcdefABCDEF") { "Invalid language pack JSON escape" }
                    val digit = next.toChar().digitToInt(16)
                    code = (code shl 4) or digit
                    advance()
                }
                code.toChar()
            }
            else -> throw IllegalArgumentException("Invalid language pack JSON escape")
        }
    }

    private fun token(literal: Boolean) {
        val output = StringBuilder()
        while (next >= 0 && next.toChar() !in ",]} \t\r\n") {
            require(output.length < MAX_TOKEN_CHARS) { "Language pack JSON token is too long" }
            output.append(next.toChar())
            advance()
        }
        val text = output.toString()
        require(if (literal) text in LITERALS else NUMBER.matches(text)) { "Invalid language pack JSON token" }
        visitor.scalar(text, false)
    }

    private fun whitespace() {
        while (next >= 0 && next.toChar() in " \t\r\n") advance()
    }

    private fun consume(character: Char): Boolean {
        if (next != character.code) return false
        advance()
        return true
    }

    private fun advance() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        next = source.read()
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val MAX_TOKEN_CHARS = 4096
        private const val MAX_OBJECT_KEYS = 50_000
        private const val MAX_ACTIVE_KEYS = 100_000
        private val LITERALS = setOf("true", "false", "null")
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
