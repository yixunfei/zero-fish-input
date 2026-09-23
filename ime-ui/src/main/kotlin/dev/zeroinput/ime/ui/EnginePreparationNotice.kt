package dev.zeroinput.ime.ui

/** One reminder per preparation attempt in the current editor. Never stores input. */
internal class EnginePreparationNotice {
    private var preparing = false
    private var notified = false

    fun update(status: InputEngineStatus) {
        preparing = status == InputEngineStatus.PREPARING
        if (!preparing) notified = false
    }

    fun onInputAttempt(): Boolean {
        if (!preparing || notified) return false
        notified = true
        return true
    }

    fun resetEditor() { notified = false }
}
