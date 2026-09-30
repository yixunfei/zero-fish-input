package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.GlideKey
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlidePoint
import dev.zeroinput.engine.api.GlideRequest
import kotlin.math.sin

/** Project-authored public paths, never captured user input or an accuracy dataset. */
internal object GlideFixtures {
    fun qwertyKeys(): List<GlideKey> = listOf("qwertyuiop", "asdfghjkl;", "zxcvbnm").flatMapIndexed { row, letters ->
        val offset = if (row == 2) 1.2f else 0f
        letters.mapIndexed { column, code ->
            GlideKey(code, (column + offset) / 10f, row / 3f, (column + offset + 1) / 10f, (row + 1) / 3f)
        }
    }

    fun nineKeys(): List<GlideKey> = ('2'..'9').map { code ->
        val cell = code - '1'
        GlideKey(code, (cell % 3) / 3f, (cell / 3) / 3f, (cell % 3 + 1) / 3f, (cell / 3 + 1) / 3f)
    }

    fun request(
        code: String,
        layout: GlideLayout = GlideLayout.ENGLISH_QWERTY,
        keys: List<GlideKey> = if (layout == GlideLayout.PINYIN_NINE_KEY) nineKeys() else qwertyKeys(),
        perturbation: Float = 0f,
        density: Int = 6,
    ): GlideRequest {
        val centers = code.filter { it != '\'' }.map { character ->
            val key = keys.first { it.code == character }
            (key.left + key.right) * 0.5f to (key.top + key.bottom) * 0.5f
        }
        val path = mutableListOf<GlidePoint>()
        centers.zipWithNext().forEach { (first, second) ->
            for (index in 0 until density) {
                val fraction = index.toFloat() / density
                val displacement = sin(fraction * Math.PI).toFloat() * perturbation
                path += GlidePoint(
                    (first.first + (second.first - first.first) * fraction + displacement).coerceIn(0f, 1f),
                    (first.second + (second.second - first.second) * fraction - displacement).coerceIn(0f, 1f),
                    path.size * 8L,
                )
            }
        }
        val last = centers.last()
        path += GlidePoint(last.first, last.second, path.size * 8L)
        if (path.size == 1) path += path[0].copy(elapsedMillis = 8)
        return GlideRequest(layout, path, keys)
    }
}
