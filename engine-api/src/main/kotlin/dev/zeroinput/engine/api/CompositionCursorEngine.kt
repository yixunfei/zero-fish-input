package dev.zeroinput.engine.api

/** Selection offsets refer only to the engine's raw input, never to an external editor. */
data class CompositionSelection(val start: Int, val end: Int = start) {
    val lower: Int get() = minOf(start, end)
    val upper: Int get() = maxOf(start, end)
    fun isValidFor(input: String): Boolean = start in 0..input.length && end in 0..input.length
    fun isAtEnd(input: String): Boolean = start == input.length && end == input.length
}

/** Optional cursor editing for a currently active engine-owned composition. */
interface CompositionCursorEngine {
    fun selectComposition(selection: CompositionSelection): EngineUpdate
}
