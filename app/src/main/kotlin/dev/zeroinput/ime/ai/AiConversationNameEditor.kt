package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits

/** Reuses the isolated keyboard draft while preserving the question in owner-scoped memory. */
internal class AiConversationNameEditor(
    private val draftText: () -> String,
    private val clearDraft: () -> Unit,
    private val appendToDraft: (String) -> Boolean,
    private val stopRequest: () -> Unit,
    private val renameConversation: (String, String) -> Unit,
    private val render: (Boolean) -> Unit,
    private val rejected: () -> Unit,
) {
    private var id: String? = null
    private var question: String? = null
    val active: Boolean get() = id != null

    fun start(id: String, title: String) {
        cancel()
        stopRequest()
        question = draftText()
        this.id = id
        clearDraft()
        if (!appendToDraft(title)) {
            cancel()
            return
        }
        render(true)
    }

    fun save() {
        val target = id ?: return
        val title = draftText().trim()
        if (title.isBlank() || title.length > AiLimits.MAX_TITLE_CHARS || title.any(Char::isISOControl)) {
            cancel()
            rejected()
            return
        }
        renameConversation(target, title)
        cancel()
    }

    fun cancel() {
        val previous = question
        reset()
        if (previous != null) { clearDraft(); appendToDraft(previous) }
    }

    fun reset() { id = null; question = null; render(false) }
}
