package dev.zeroinput.ime.settings

import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyboardStylePresetsTest {
    @Test fun defaultPresetMatchesTheDefaultKeyboard() {
        assertEquals(KeyboardAppearance(), KeyboardStylePresets.apply(KeyboardAppearance(), KeyboardTheme.CLASSIC))
    }

    @Test fun applyingStylePreservesUserImageAndInputGeometry() {
        val initial = KeyboardAppearance(height = KeyboardHeight.COMFORTABLE,
            background = KeyboardBackground.IMAGE, imageRevision = "12345678-1234-1234-1234-123456789abc",
            backgroundBlur = 12, backgroundDim = 27, backgroundOpacity = 82, backgroundColor = 0xffbed5ef.toInt())
        KeyboardTheme.entries.forEach { theme ->
            val selected = KeyboardStylePresets.apply(initial, theme)
            assertEquals(initial.height, selected.height)
            assertEquals(initial.expandedCandidates, selected.expandedCandidates)
            assertEquals(initial.background, selected.background)
            assertEquals(initial.imageRevision, selected.imageRevision)
            assertEquals(initial.backgroundBlur, selected.backgroundBlur)
            assertEquals(initial.backgroundDim, selected.backgroundDim)
            assertEquals(initial.backgroundOpacity, selected.backgroundOpacity)
            assertEquals(initial.backgroundColor, selected.backgroundColor)
            assertEquals(selected, selected.sanitized())
            assertEquals(selected, KeyboardStylePresets.apply(selected, theme))
        }
        assertNotEquals(KeyboardStylePresets.apply(initial, KeyboardTheme.MINT),
            KeyboardStylePresets.apply(initial, KeyboardTheme.ROSE))
    }
}
