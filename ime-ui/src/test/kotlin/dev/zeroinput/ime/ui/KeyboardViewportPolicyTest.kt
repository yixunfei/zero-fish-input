package dev.zeroinput.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardViewportPolicyTest {
    @Test fun unmeasuredViewportKeepsTheBaselineLayoutUntilARealSizeArrives() {
        val plan = KeyboardViewportPolicy.resolve(widthPx = 0, heightPx = 0, density = 1f, editing = false)

        assertFalse(plan.candidateInline)
        assertEquals(CandidateActionDensity.FULL, plan.candidateActions)
        assertFalse(plan.compactKeyboard)
    }

    @Test fun narrowLandscapeKeepsCandidateBrowserAndDoesNotCompressKeyColumns() {
        val plan = KeyboardViewportPolicy.resolve(widthPx = 360, heightPx = 280, density = 1f, editing = false)

        assertTrue(plan.candidateInline)
        assertEquals(CandidateActionDensity.COMPACT, plan.candidateActions)
        assertFalse(plan.compactKeyboard)
    }

    @Test fun shortWideViewportUsesCompactRowsAndInlineCandidateHeader() {
        val plan = KeyboardViewportPolicy.resolve(widthPx = 960, heightPx = 280, density = 1f, editing = false)

        assertTrue(plan.candidateInline)
        assertEquals(KeyboardViewportPolicy.INLINE_HEADER_HEIGHT_DP, plan.headerHeightDp)
        assertTrue(plan.compactKeyboard)
    }

    @Test fun fullscreenPortraitKeepsTheNormalKeyboardAndStackedCandidates() {
        val plan = KeyboardViewportPolicy.resolve(widthPx = 420, heightPx = 900, density = 1f, editing = false)

        assertFalse(plan.candidateInline)
        assertEquals(KeyboardViewportPolicy.STACKED_HEADER_HEIGHT_DP, plan.headerHeightDp)
        assertFalse(plan.compactKeyboard)
    }

    @Test fun wideEditingViewportPlacesDetailBesideKeys() {
        val plan = KeyboardViewportPolicy.resolve(widthPx = 1200, heightPx = 600, density = 1f, editing = true)

        assertTrue(plan.splitDetailPanel)
    }

    @Test fun extremelyNarrowViewportKeepsCommandsOutOfTheCandidateBrowser() {
        val plan = KeyboardViewportPolicy.resolve(widthPx = 220, heightPx = 400, density = 1f, editing = false)

        assertEquals(CandidateActionDensity.MINIMAL, plan.candidateActions)
    }
}
