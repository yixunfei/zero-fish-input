package dev.zeroinput.ime.ui

sealed interface AiContextCommand {
    val revision: Long
    data class History(override val revision: Long, val index: Int, val included: Boolean) : AiContextCommand
    data class Recent(override val revision: Long) : AiContextCommand
    data class Clear(override val revision: Long) : AiContextCommand
    data class Remove(override val revision: Long, val index: Int) : AiContextCommand
}
