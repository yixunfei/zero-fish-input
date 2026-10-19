package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.DoublePinyinScheme

/** Worker-side spelling and script conversion for encrypted dictionary overlays. */
object DictionaryEditReading {
    fun matches(reading: String, prefix: String, options: ChineseInputOptions): Boolean {
        if (reading.isEmpty()) return false
        val raw = prefix.replace("'", "").replace(" ", "").lowercase()
        if (raw.isEmpty() || raw.length > 128) return false
        var positions = setOf(0)
        for (syllable in reading.split(' ')) {
            val codes = FuzzyPinyinMatcher.variants(syllable, options).flatMap { variant ->
                val code = if (options.effectiveDoublePinyinScheme == DoublePinyinScheme.OFF) variant
                    else DoublePinyin.encode(variant, options.effectiveDoublePinyinScheme) ?: return@flatMap emptyList()
                val spellings = if (options.abbreviatedPinyin) listOf(code, code.take(1),
                    code.take(2).takeIf { it in listOf("zh", "ch", "sh") }.orEmpty()).filter { it.isNotEmpty() }
                    else listOf(code)
                if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY) spellings + spellings.map(::digits) else spellings
            }.distinct()
            val next = HashSet<Int>()
            for (start in positions) for (code in codes) {
                val remaining = raw.length - start
                if (remaining <= code.length && code.regionMatches(0, raw, start, remaining)) return true
                if (raw.startsWith(code, start)) next += start + code.length
            }
            positions = next
            if (positions.isEmpty()) return false
        }
        return raw.length in positions
    }

    fun displayText(text: String, script: ChineseScript): String = try {
        NativeRimeBridge.nativeConvertText(text, script == ChineseScript.SIMPLIFIED)
    } catch (_: IllegalStateException) { text } catch (_: UnsatisfiedLinkError) { text }

    private fun digits(text: String): String = text.map { character ->
        when (character) {
            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; else -> '9'
        }
    }.joinToString("")
}
