package dev.zeroinput.languagepack

import java.io.Reader
import java.util.concurrent.CancellationException

/** Strict JSON events shared by manifest validation and streaming dictionary loading. */
internal class LanguagePackJsonReader(private val source: Reader, private val visitor: Visitor = Visitor.None,
                                     private val skipOversizedScalars: Boolean = false) {
    interface Visitor {
        fun beginObject() = Unit
        fun name(value: String) = Unit
        fun endObject() = Unit
        fun beginArray() = Unit
        fun endArray() = Unit
        fun scalar(value: String, quoted: Boolean) = Unit
        fun oversizedScalar(quoted: Boolean) = Unit
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
            '"'.code -> {
                val text = string(skipOversizedScalars)
                if (text == null) visitor.oversizedScalar(true) else visitor.scalar(text, true)
            }
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
                val key = requireNotNull(string())
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

    private fun string(skipOversized: Boolean = false): String? {
        require(consume('"')) { "JSON string must start with a quote" }
        var output: StringBuilder? = StringBuilder()
        while (next >= 0) {
            val character = next.toChar()
            advance()
            when {
                character == '"' -> return output?.toString()
                character < ' ' -> throw IllegalArgumentException("Invalid language pack JSON string")
                character == '\\' -> { val escaped = escape(); output?.append(escaped) }
                else -> output?.append(character)
            }
            if (output != null && output.length > MAX_TOKEN_CHARS) {
                require(skipOversized) { "Language pack JSON token is too long" }
                output = null // Still validate the complete string and escape syntax without retaining it.
            }
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
        var output: StringBuilder? = StringBuilder()
        fun append() {
            output?.append(next.toChar())
            if (output?.length == MAX_TOKEN_CHARS + 1) {
                require(skipOversizedScalars && !literal) { "Language pack JSON token is too long" }
                output = null
            }
            advance()
        }
        if (literal) {
            while (next >= 0 && next.toChar() !in ",]} \t\r\n") append()
            require(output.toString() in LITERALS) { "Invalid language pack JSON token" }
        } else {
            if (next == '-'.code) append()
            require(next in '0'.code..'9'.code) { "Invalid JSON number" }
            if (next == '0'.code) append() else while (next in '0'.code..'9'.code) append()
            if (next == '.'.code) {
                append()
                require(next in '0'.code..'9'.code) { "Invalid JSON fraction" }
                while (next in '0'.code..'9'.code) append()
            }
            if (next == 'e'.code || next == 'E'.code) {
                append()
                if (next == '+'.code || next == '-'.code) append()
                require(next in '0'.code..'9'.code) { "Invalid JSON exponent" }
                while (next in '0'.code..'9'.code) append()
            }
            require(next < 0 || next.toChar() in ",]} \t\r\n") { "Invalid JSON number delimiter" }
        }
        val text = output?.toString()
        if (text == null) visitor.oversizedScalar(false) else visitor.scalar(text, false)
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
    }
}
