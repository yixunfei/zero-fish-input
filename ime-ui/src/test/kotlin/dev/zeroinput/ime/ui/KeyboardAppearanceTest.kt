package dev.zeroinput.ime.ui

import org.junit.Assert.*
import org.junit.Test

class KeyboardAppearanceTest {
    @Test fun malformedPersistedGeometryAndOpacityRemainBounded() {
        val value = KeyboardAppearance(cornerRadius = 999, keySpacing = -3, backgroundOpacity = -1,
            backgroundDim = 500, backgroundBlur = 90, imageRevision = "../../photo").sanitized()
        assertEquals(20, value.cornerRadius)
        assertEquals(0, value.keySpacing)
        assertEquals(0, value.backgroundOpacity)
        assertEquals(80, value.backgroundDim)
        assertEquals(20, value.backgroundBlur)
        assertEquals("", value.imageRevision)
    }

    @Test fun customColorsCannotMakeTheKeyboardWindowTransparent() {
        assertEquals(0xff112233.toInt(), KeyboardAppearance(backgroundColor = 0x00112233).sanitized().backgroundColor)
    }

    @Test fun validImageIdentityAndIndependentStyleChoicesSurviveSanitizing() {
        val value = KeyboardAppearance(theme = KeyboardTheme.ROSE, material = KeyboardMaterial.METAL,
            borders = true, imageRevision = "550e8400-e29b-41d4-a716-446655440000")
        assertEquals(value, value.sanitized())
        assertEquals(KeyboardMaterial.FLAT, KeyboardAppearance().material)
    }
}
