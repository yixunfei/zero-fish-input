package dev.zeroinput.ime.ui

enum class ExpandedCandidateLayout(val label: Int) {
    ADAPTIVE(R.string.expanded_candidates_adaptive), LIST(R.string.expanded_candidates_list),
}

enum class ExpandedCandidateDensity(
    val label: Int,
    internal val minimumWidth: Int,
    internal val minimumHeight: Int,
    internal val horizontalPadding: Int,
    internal val verticalPadding: Int,
) {
    COMPACT(R.string.expanded_candidates_compact, 48, 48, 6, 4),
    STANDARD(R.string.expanded_candidates_standard, 72, 56, 12, 8),
    SPACIOUS(R.string.expanded_candidates_spacious, 96, 64, 18, 12),
}

/** Presentation only; candidate identity, ranking and paging remain owned by the session. */
data class ExpandedCandidateAppearance(
    val layout: ExpandedCandidateLayout = ExpandedCandidateLayout.ADAPTIVE,
    val density: ExpandedCandidateDensity = ExpandedCandidateDensity.SPACIOUS,
    val textSize: Int = 18,
    val separators: Boolean = true,
) {
    fun sanitized(): ExpandedCandidateAppearance = copy(textSize = textSize.coerceIn(16, 24))
}
