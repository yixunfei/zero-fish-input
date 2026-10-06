package dev.zeroinput.ime.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.KeyboardHeight
import dev.zeroinput.ime.ui.KeyboardMaterial
import dev.zeroinput.ime.ui.KeyboardTheme

/** Nonsensitive appearance identifiers; image bytes never enter preferences. */
internal class AppearancePreferences(private val preferences: SharedPreferences) {
    fun read() = KeyboardAppearance(
        theme = enumValue("theme", KeyboardTheme.CLASSIC),
        height = enumValue("height", KeyboardHeight.STANDARD),
        material = enumValue("material", KeyboardMaterial.FLAT),
        borders = preferences.getBoolean("keyboard.borders", false),
        cornerRadius = number("radius", 8), keySpacing = number("spacing", 2),
        background = enumValue("background", KeyboardBackground.SOLID),
        backgroundColor = if (preferences.contains("keyboard.background-color")) number("background-color", 0) else null,
        backgroundOpacity = number("opacity", 100), backgroundDim = number("dim", 15),
        backgroundBlur = number("blur", 0), imageRevision = preferences.getString("keyboard.image", "").orEmpty(),
    ).sanitized()

    fun write(appearance: KeyboardAppearance) {
        val value = appearance.sanitized()
        preferences.edit {
            putString("keyboard.theme", value.theme.name)
            putString("keyboard.height", value.height.name)
            putString("keyboard.material", value.material.name)
            putBoolean("keyboard.borders", value.borders)
            putInt("keyboard.radius", value.cornerRadius)
            putInt("keyboard.spacing", value.keySpacing)
            putString("keyboard.background", value.background.name)
            value.backgroundColor?.let { putInt("keyboard.background-color", it) } ?: remove("keyboard.background-color")
            putInt("keyboard.opacity", value.backgroundOpacity)
            putInt("keyboard.dim", value.backgroundDim)
            putInt("keyboard.blur", value.backgroundBlur)
            putString("keyboard.image", value.imageRevision)
        }
    }

    private fun number(key: String, default: Int) = preferences.getInt("keyboard.$key", default)
    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == preferences.getString("keyboard.$key", null) } ?: default
}
