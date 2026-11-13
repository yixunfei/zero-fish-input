package dev.zeroinput.ime.settings

import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardMaterial
import dev.zeroinput.ime.ui.KeyboardTheme

/** Presets only replace key decoration; layout, candidate and user image choices survive. */
internal object KeyboardStylePresets {
    fun apply(current: KeyboardAppearance, theme: KeyboardTheme): KeyboardAppearance = current.copy(
        theme = theme,
        material = when (theme) {
            KeyboardTheme.CLASSIC, KeyboardTheme.GRAY -> KeyboardMaterial.FLAT
            KeyboardTheme.MINT -> KeyboardMaterial.SOFT
            KeyboardTheme.ROSE -> KeyboardMaterial.RAISED
        },
        cornerRadius = when (theme) {
            KeyboardTheme.CLASSIC -> 8
            KeyboardTheme.GRAY -> 4
            KeyboardTheme.MINT -> 12
            KeyboardTheme.ROSE -> 8
        },
        keySpacing = 2,
        borders = theme == KeyboardTheme.GRAY,
    )
}
