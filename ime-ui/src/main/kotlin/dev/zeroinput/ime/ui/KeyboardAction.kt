package dev.zeroinput.ime.ui

sealed interface KeyboardAction {
    data class Text(val value: String) : KeyboardAction

    data class LiteralText(val value: String) : KeyboardAction

    data class PairedText(val opening: String, val closing: String) : KeyboardAction

    data object Backspace : KeyboardAction

    data object Shift : KeyboardAction

    data object Space : KeyboardAction

    data object Enter : KeyboardAction

    data object SwitchLanguage : KeyboardAction

    data object ShowLetters : KeyboardAction

    data object ShowSymbols : KeyboardAction

    data object ShowMoreSymbols : KeyboardAction

    data object ShowEmoji : KeyboardAction

    data object ShowSecureClipboard : KeyboardAction

    data object OpenSettings : KeyboardAction
}

internal enum class KeyStyle {
    NORMAL,
    MODIFIER,
    PRIMARY,
}

internal data class KeySpec(
    val label: String,
    val contentDescription: String,
    val action: KeyboardAction,
    val widthWeight: Float = 1f,
    val style: KeyStyle = KeyStyle.NORMAL,
    val enabled: Boolean = true,
)

internal enum class KeyboardPage {
    LETTERS,
    SYMBOLS,
    MORE_SYMBOLS,
    NUMERIC,
}
