package dev.zeroinput.ime.clipboard

/** Owns one transient draft. No callback or Android component is retained here. */
internal class ClipboardImportRequest(private val text: CharArray) : AutoCloseable {
    enum class Phase { REVIEW, AUTHENTICATING, AUTHORIZED, SAVING, CLOSED }

    val length = text.size

    @Volatile
    var phase = Phase.REVIEW
        private set

    @Synchronized
    fun beginAuthentication(): Boolean = transition(Phase.REVIEW, Phase.AUTHENTICATING)

    @Synchronized
    fun authorize(): Boolean = transition(Phase.AUTHENTICATING, Phase.AUTHORIZED)

    @Synchronized
    fun beginSave(): Boolean = transition(Phase.AUTHORIZED, Phase.SAVING)

    @Synchronized
    fun copyText(): String? = when (phase) {
        Phase.AUTHORIZED, Phase.SAVING -> String(text)
        else -> null
    }

    /** Copies the user-selected share text for the separate AI context confirmation. */
    @Synchronized
    fun copyForAi(): String? = if (phase == Phase.REVIEW) String(text) else null

    fun isSaving(): Boolean = phase == Phase.SAVING

    @Synchronized
    override fun close() {
        phase = Phase.CLOSED
        text.fill('\u0000')
    }

    private fun transition(expected: Phase, next: Phase): Boolean {
        if (phase != expected) return false
        phase = next
        return true
    }
}
