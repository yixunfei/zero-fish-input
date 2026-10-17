package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions

/** Keeps accepted short codes while rejecting correction-only spellings. */
internal object RimeLearningInput {
    fun choose(raw: String, reading: String, options: ChineseInputOptions): String =
        raw.takeIf { it.length in 1..128 && DictionaryEditReading.matches(reading, it, options) }
            ?: reading.filterNot { it == ' ' || it == '\'' }
}
