package dev.zeroinput.languagepack

import org.json.JSONTokener

/** Bounds parser recursion before org.json allocates the untrusted document tree. */
internal object LanguagePackJson {
    private const val MAX_DEPTH = 16

    fun parse(document: String): Any {
        val normalized = document.removePrefix("\uFEFF")
        StrictJsonScanner(normalized).parse()
        val parser = JSONTokener(normalized)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "Language pack JSON has trailing data" }
        return value
    }

    private const val WHITESPACE = " \t\r\n"
    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
    private val LITERALS = setOf("true", "false", "null")
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private class StrictJsonScanner(private val source: String) {
        private var index = 0

        fun parse() {
            parseValue(0)
            skipWhitespace()
            require(index == source.length) { "Language pack JSON has trailing data" }
        }

        private fun parseValue(depth: Int) {
            skipWhitespace()
            require(index < source.length) { "Incomplete language pack JSON" }
            when (source[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> readString()
                't', 'f', 'n' -> readLiteral()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Invalid language pack JSON token")
            }
        }

        private fun parseObject(depth: Int) {
            require(depth <= MAX_DEPTH) { "Language pack JSON is too deeply nested" }
            index++
            skipWhitespace()
            val keys = HashSet<String>()
            if (consume('}')) return
            while (true) {
                skipWhitespace()
                require(index < source.length && source[index] == '"') { "Object keys must be quoted" }
                val key = readString(capture = true)
                require(keys.add(key)) { "Duplicate JSON object key" }
                skipWhitespace()
                require(consume(':')) { "Missing JSON object separator" }
                parseValue(depth)
                skipWhitespace()
                if (consume('}')) return
                require(consume(',')) { "Missing JSON object delimiter" }
                skipWhitespace()
                require(index < source.length && source[index] != '}') { "Trailing JSON object delimiter" }
            }
        }

        private fun parseArray(depth: Int) {
            require(depth <= MAX_DEPTH) { "Language pack JSON is too deeply nested" }
            index++
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                parseValue(depth)
                skipWhitespace()
                if (consume(']')) return
                require(consume(',')) { "Missing JSON array delimiter" }
                skipWhitespace()
                require(index < source.length && source[index] != ']') { "Trailing JSON array delimiter" }
            }
        }

        private fun readString(capture: Boolean = false): String {
            require(consume('"')) { "JSON string must start with a quote" }
            val value = if (capture) StringBuilder() else null
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return value?.toString().orEmpty()
                    character < ' ' -> throw IllegalArgumentException("Invalid language pack JSON string")
                    character != '\\' -> value?.append(character)
                    else -> {
                        val escaped = readEscape()
                        value?.append(escaped)
                    }
                }
            }
            throw IllegalArgumentException("Incomplete language pack JSON string")
        }

        private fun readEscape(): Char {
            require(index < source.length) { "Incomplete language pack JSON escape" }
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= source.length) { "Incomplete language pack JSON escape" }
                    val code = source.substring(index, index + 4)
                    require(code.all { it in HEX_DIGITS }) { "Invalid language pack JSON escape" }
                    index += 4
                    code.toInt(16).toChar()
                }
                else -> throw IllegalArgumentException("Invalid language pack JSON escape")
            }
        }

        private fun readLiteral() {
            val start = index
            while (index < source.length && source[index].isLetter()) index++
            require(source.substring(start, index) in LITERALS) { "Invalid language pack JSON token" }
        }

        private fun readNumber() {
            val start = index
            while (index < source.length && source[index] !in ",]}$WHITESPACE") index++
            require(NUMBER.matches(source.substring(start, index))) { "Invalid language pack JSON number" }
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in WHITESPACE) index++
        }

        private fun consume(character: Char): Boolean {
            if (index >= source.length || source[index] != character) return false
            index++
            return true
        }
    }
}
