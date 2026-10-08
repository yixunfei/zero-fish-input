package dev.zeroinput.ime.ui

/**
 * Layout decisions derived from the IME's measured viewport rather than the
 * device configuration. This keeps split-screen and freeform windows stable.
 */
internal data class KeyboardViewportPlan(
    val headerHeightDp: Int,
    val candidateInline: Boolean,
    val candidateActions: CandidateActionDensity,
    val compactKeyboard: Boolean,
    val splitDetailPanel: Boolean,
)

internal enum class CandidateActionDensity { FULL, COMPACT, MINIMAL }

internal object KeyboardViewportPolicy {
    fun resolve(widthPx: Int, heightPx: Int, density: Float, editing: Boolean): KeyboardViewportPlan {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val width = widthPx.coerceAtLeast(0) / safeDensity
        val height = heightPx.coerceAtLeast(0) / safeDensity
        if (width == 0f || height == 0f) {
            return KeyboardViewportPlan(
                headerHeightDp = STACKED_HEADER_HEIGHT_DP,
                candidateInline = false,
                candidateActions = CandidateActionDensity.FULL,
                compactKeyboard = false,
                splitDetailPanel = false,
            )
        }
        val narrow = width < NARROW_WIDTH_DP
        val short = height > 0f && height < if (editing) EDITING_COMPACT_HEIGHT_DP else COMPACT_HEIGHT_DP
        val wideViewport = width > 0f && height > 0f && width >= height * WIDE_VIEWPORT_RATIO
        return KeyboardViewportPlan(
            headerHeightDp = if (wideViewport || short) INLINE_HEADER_HEIGHT_DP else STACKED_HEADER_HEIGHT_DP,
            candidateInline = wideViewport || short,
            candidateActions = when {
                width < MINIMAL_ACTION_WIDTH_DP -> CandidateActionDensity.MINIMAL
                narrow || short -> CandidateActionDensity.COMPACT
                else -> CandidateActionDensity.FULL
            },
            compactKeyboard = short && !narrow,
            splitDetailPanel = editing && wideViewport && !narrow,
        )
    }

    private const val NARROW_WIDTH_DP = 460f
    private const val MINIMAL_ACTION_WIDTH_DP = 260f
    private const val COMPACT_HEIGHT_DP = 360f
    private const val EDITING_COMPACT_HEIGHT_DP = 500f
    private const val WIDE_VIEWPORT_RATIO = 1.25f
    const val INLINE_HEADER_HEIGHT_DP = 48
    const val STACKED_HEADER_HEIGHT_DP = 72
}
