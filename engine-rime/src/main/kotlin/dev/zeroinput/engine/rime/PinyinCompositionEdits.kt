package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot

/** Plans bounded edits without giving a rejected edit a path to the external editor. */
internal object PinyinCompositionEdits {
    data class Replacement(val input: String, val caret: Int)

    fun replacement(snapshot: EngineSnapshot, key: EngineKey, microsoft: Boolean): Replacement? {
        val selection = snapshot.selection ?: return null
        val input = snapshot.rawInput
        if (!selection.isValidFor(input)) return Replacement(input, input.length)
        val text = when (key) {
            is EngineKey.Character -> key.text.singleOrNull()?.lowercaseChar()?.takeIf {
                it in 'a'..'z' || it == '\'' || microsoft && it == ';' || it in '2'..'9'
            }?.toString() ?: return null
            EngineKey.Backspace -> ""
            else -> return null
        }
        if (selection.isAtEnd(input)) return null
        val lower = if (key == EngineKey.Backspace && selection.start == selection.end)
            (selection.lower - 1).coerceAtLeast(0) else selection.lower
        if (input.length - (selection.upper - lower) + text.length > 128)
            return Replacement(input, selection.end)
        return Replacement(input.replaceRange(lower, selection.upper, text), lower + text.length)
    }
}
