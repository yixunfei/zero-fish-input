package dev.zeroinput.ime.ui

enum class KeyboardPlacementMode { DOCKED, LEFT_HAND, RIGHT_HAND, FLOATING }

/** Public geometry preferences only; no editor identity or typed content. */
data class KeyboardPlacement(
    val mode: KeyboardPlacementMode = KeyboardPlacementMode.DOCKED,
    val oneHandWidth: Float = 0.82f,
    val floatingWidth: Float = 0.86f,
    val horizontalPosition: Float = 0.5f,
    val verticalPosition: Float = 0.8f,
    val heightScale: Float = 1f,
) {
    fun sanitized() = copy(
        oneHandWidth = oneHandWidth.safe(0.6f, 0.95f, 0.82f),
        floatingWidth = floatingWidth.safe(0.5f, 1f, 0.86f),
        horizontalPosition = horizontalPosition.safe(0f, 1f, 0.5f),
        verticalPosition = verticalPosition.safe(0f, 1f, 0.8f),
        heightScale = heightScale.safe(0.8f, 1.4f, 1f),
    )

    private fun Float.safe(low: Float, high: Float, fallback: Float) =
        if (isFinite()) coerceIn(low, high) else fallback
}

/** Geometry is independent of Android so clipping/rotation rules can be tested directly. */
object KeyboardPlacementGeometry {
    fun width(placement: KeyboardPlacement, available: Int, minimum: Int): Int {
        val value = placement.sanitized()
        val fraction = when (value.mode) {
            KeyboardPlacementMode.DOCKED -> 1f
            KeyboardPlacementMode.LEFT_HAND, KeyboardPlacementMode.RIGHT_HAND -> value.oneHandWidth
            KeyboardPlacementMode.FLOATING -> value.floatingWidth
        }
        return (available * fraction).toInt().coerceIn(minOf(minimum, available), available)
    }

    fun position(placement: KeyboardPlacement, availableWidth: Int, availableHeight: Int,
        panelWidth: Int, panelHeight: Int): Pair<Int, Int> {
        val state = placement.sanitized()
        val freeX = (availableWidth - panelWidth).coerceAtLeast(0)
        val freeY = (availableHeight - panelHeight).coerceAtLeast(0)
        val x = when (state.mode) {
            KeyboardPlacementMode.DOCKED, KeyboardPlacementMode.LEFT_HAND -> 0
            KeyboardPlacementMode.RIGHT_HAND -> freeX
            KeyboardPlacementMode.FLOATING -> (freeX * state.horizontalPosition).toInt()
        }
        return x to if (state.mode == KeyboardPlacementMode.FLOATING)
            (freeY * state.verticalPosition).toInt() else freeY
    }
}
