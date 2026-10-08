package dev.zeroinput.ai.api

/** Source text is data, never an instruction or an executable message role. */
class AiReference(val text: String, val source: Source = Source.PAGE) {
    enum class Source { PAGE, IMPORT }

    init {
        require(text.isNotBlank() && text.length <= MAX_REFERENCE_CHARS) { "Invalid AI reference" }
        require(text.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid AI reference" }
    }

    override fun toString() = "AiReference(redacted)"

    companion object {
        const val MAX_REFERENCES = 16
        const val MAX_REFERENCE_CHARS = 4_096
        const val MAX_CONTEXT_CHARS = 16_384
        const val MAX_CONTEXT_MESSAGES = 12

        fun validate(history: List<AiMessage>, references: List<AiReference>) {
            require(history.size <= MAX_CONTEXT_MESSAGES && history.none { it.role == AiRole.SYSTEM }) {
                "Invalid selected AI history"
            }
            require(references.size <= MAX_REFERENCES) { "Too many AI references" }
            require(history.sumOf { it.content.length } + references.sumOf { it.text.length } <= MAX_CONTEXT_CHARS) {
                "Selected AI context is too large"
            }
        }
    }
}

/** An owner-issued revision prevents a stale checklist from changing a later chat. */
class AiContextState(
    val revision: Long = 0,
    val messages: List<AiMessage> = emptyList(),
    val selectedHistory: Set<Int> = emptySet(),
    val references: List<AiReference> = emptyList(),
) {
    val characterCount: Int get() = messages.filterIndexed { index, _ -> index in selectedHistory }
        .sumOf { it.content.length } + references.sumOf { it.text.length }
    override fun toString() = "AiContextState(redacted)"
}
