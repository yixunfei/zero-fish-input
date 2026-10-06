package dev.zeroinput.ime.ui

import org.junit.Assert.*
import org.junit.Test

class PanelSizePolicyTest {
    @Test fun expandedPanelUsesAtLeastHalfOfTheCurrentWindow() {
        for (height in listOf(320, 600, 914, 1600)) {
            val body = PanelSizePolicy.bodyHeight(PanelExpansion.EXPANDED, height, 72, 260)
            assertTrue(body + 72 >= height / 2)
            assertTrue(body + 72 <= height)
        }
    }

    @Test fun fullscreenUsesAllAvailableSpaceAndCollapseRestoresTheCompactHeight() {
        assertEquals(842, PanelSizePolicy.bodyHeight(PanelExpansion.FULLSCREEN, 914, 72, 260))
        assertEquals(260, PanelSizePolicy.bodyHeight(PanelExpansion.COMPACT, 914, 72, 260))
        assertEquals(0, PanelSizePolicy.bodyHeight(PanelExpansion.FULLSCREEN, 48, 72, 260))
    }
}
