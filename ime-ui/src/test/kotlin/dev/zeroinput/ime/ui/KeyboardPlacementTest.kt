package dev.zeroinput.ime.ui

import org.junit.Assert.*
import org.junit.Test

class KeyboardPlacementTest {
    @Test fun invalidStoredGeometryCannotEscapeSafeBounds() {
        val placement = KeyboardPlacement(KeyboardPlacementMode.FLOATING, floatingWidth = Float.NaN,
            horizontalPosition = Float.POSITIVE_INFINITY, verticalPosition = -5f, heightScale = 900f).sanitized()
        assertEquals(0.86f, placement.floatingWidth)
        assertEquals(0.5f, placement.horizontalPosition)
        assertEquals(0f, placement.verticalPosition)
        assertEquals(1.4f, placement.heightScale)
    }

    @Test fun movingAndRotatingAlwaysKeepsPanelInsideViewport() {
        for ((width, height) in listOf(320 to 600, 600 to 320, 200 to 220, 800 to 1200)) {
            for (x in listOf(-1f, 0f, 0.5f, 1f, 2f)) for (y in listOf(-1f, 0f, 1f, 2f)) {
                val placement = KeyboardPlacement(KeyboardPlacementMode.FLOATING, horizontalPosition = x, verticalPosition = y)
                val panelWidth = KeyboardPlacementGeometry.width(placement, width, 240)
                val panelHeight = minOf(height, 280)
                val point = KeyboardPlacementGeometry.position(placement, width, height, panelWidth, panelHeight)
                assertTrue(point.first >= 0 && point.first + panelWidth <= width)
                assertTrue(point.second >= 0 && point.second + panelHeight <= height)
            }
        }
    }

    @Test fun switchingHandsRetainsWidthAndAnchorsOppositeEdges() {
        val left = KeyboardPlacement(KeyboardPlacementMode.LEFT_HAND)
        val right = left.copy(mode = KeyboardPlacementMode.RIGHT_HAND)
        val width = KeyboardPlacementGeometry.width(left, 400, 240)
        assertEquals(width, KeyboardPlacementGeometry.width(right, 400, 240))
        assertEquals(0 to 0, KeyboardPlacementGeometry.position(left, 400, 300, width, 300))
        assertEquals((400 - width) to 0, KeyboardPlacementGeometry.position(right, 400, 300, width, 300))
    }

    @Test fun tinyWindowsNeverProduceNegativeOrOversizedWidths() {
        for (width in listOf(0, 1, 160, 320)) for (mode in KeyboardPlacementMode.entries) {
            assertTrue(KeyboardPlacementGeometry.width(KeyboardPlacement(mode), width, 240) in 0..width)
        }
    }
}
