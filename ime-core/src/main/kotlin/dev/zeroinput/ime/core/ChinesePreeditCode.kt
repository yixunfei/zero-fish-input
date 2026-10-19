package dev.zeroinput.ime.core

/** Raw Chinese pre-edit codes supported by full, nine-key and double pinyin. */
object ChinesePreeditCode {
    fun accepts(value: String): Boolean = value.isNotEmpty() && value.all(::acceptsCharacter)

    internal fun acceptsCharacter(value: Char): Boolean =
        value in 'a'..'z' || value in '2'..'9' || value == '\'' || value == ';'
}
