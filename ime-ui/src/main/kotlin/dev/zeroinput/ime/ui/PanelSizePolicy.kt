package dev.zeroinput.ime.ui

internal enum class PanelExpansion { COMPACT, EXPANDED, FULLSCREEN }

/** Pixel budgets from the current parent constraints, never the previous window size. */
internal object PanelSizePolicy {
    fun bodyHeight(expansion: PanelExpansion, available: Int, header: Int, compact: Int): Int {
        val bodyLimit = (available - header).coerceAtLeast(0)
        val desired = when (expansion) {
            PanelExpansion.COMPACT -> compact
            PanelExpansion.EXPANDED -> maxOf(compact, (available * 0.65f).toInt() - header)
            PanelExpansion.FULLSCREEN -> bodyLimit
        }
        return desired.coerceIn(0, bodyLimit)
    }
}
