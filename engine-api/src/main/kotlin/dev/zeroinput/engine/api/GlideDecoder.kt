package dev.zeroinput.engine.api

import java.util.Collections

enum class GlideLayout {
    ENGLISH_QWERTY, PINYIN_QWERTY, DOUBLE_PINYIN_MICROSOFT, DOUBLE_PINYIN_ZIRANMA, PINYIN_NINE_KEY,
}

/** Coordinates share the actual keyboard's normalized bounds, including row offsets. */
data class GlidePoint(val x: Float, val y: Float, val elapsedMillis: Long) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        require(elapsedMillis in 0..MAX_DURATION_MILLIS)
    }

    companion object { const val MAX_DURATION_MILLIS = 30_000L }
}

data class GlideKey(val code: Char, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(code in 'a'..'z' || code in '2'..'9' || code == ';')
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right - left >= 0.001f && bottom - top >= 0.001f)
    }
}

/** Owned snapshots: neither the collector nor the decoder may mutate a submitted request. */
class GlideRequest(
    val layout: GlideLayout,
    points: List<GlidePoint>,
    keys: List<GlideKey>,
    val maxCandidates: Int = 8,
) {
    val points: List<GlidePoint> = Collections.unmodifiableList(ArrayList(points))
    val keys: List<GlideKey> = Collections.unmodifiableList(ArrayList(keys))

    init {
        require(points.size in 2..MAX_POINTS && keys.size in 1..MAX_KEYS)
        require(maxCandidates in 1..MAX_CANDIDATES)
        require(points.zipWithNext().all { (first, second) -> first.elapsedMillis <= second.elapsedMillis })
        require(keys.map { it.code }.distinct().size == keys.size)
    }

    companion object {
        const val MAX_POINTS = 256
        const val MAX_KEYS = 40
        const val MAX_CANDIDATES = 16
    }
}

/** Chinese codes are replayed through the active engine; display hints are never committed as Han text. */
data class GlideCandidate(val inputCode: String, val displayText: String, val score: Float)

/** A public, non-personal dictionary row. Larger frequency values are more common. */
data class GlideLexiconEntry(
    val layout: GlideLayout,
    val inputCode: String,
    val displayText: String = inputCode,
    val frequency: Int = 1,
) {
    init {
        require(inputCode.length in 1..MAX_CODE_LENGTH && frequency >= 0)
        require(inputCode.all { character ->
            when (layout) {
                GlideLayout.ENGLISH_QWERTY -> character in 'a'..'z' || character == '\''
                GlideLayout.PINYIN_NINE_KEY -> character in '2'..'9'
                GlideLayout.DOUBLE_PINYIN_MICROSOFT -> character in 'a'..'z' || character == ';'
                else -> character in 'a'..'z'
            }
        })
        require(inputCode.any { it != '\'' })
        require(displayText.length in 1..MAX_LABEL_LENGTH && displayText.none(Char::isISOControl))
    }

    companion object {
        const val MAX_CODE_LENGTH = 48
        const val MAX_LABEL_LENGTH = 96
    }
}

/** Worker-only bounded decoding. Cancellation returns no candidates and retains no trace. */
fun interface GlideDecoder {
    fun decode(request: GlideRequest, isCancelled: () -> Boolean): List<GlideCandidate>
}
