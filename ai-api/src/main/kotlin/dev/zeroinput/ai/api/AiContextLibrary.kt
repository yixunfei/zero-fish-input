package dev.zeroinput.ai.api

/** Explicitly authored preferences; never a permission or executable message role. */
data class AiPersona(val id: String, val name: String, val description: String, val instructions: String) {
    init {
        contextText(id, 128); contextText(name, 80)
        contextText(description, 512, empty = true); contextText(instructions, 4_096)
    }
    override fun toString() = "AiPersona(redacted)"
}

/** A null scope means deliberately shared; UI creation defaults to the active persona. */
data class AiMemory(val id: String, val personaId: String?, val text: String) {
    init { contextText(id, 128); personaId?.let { contextText(it, 128) }; contextText(text, 2_048) }
    override fun toString() = "AiMemory(redacted)"
}

class AiContextLibrary(
    val revision: Long = 0,
    personas: List<AiPersona> = emptyList(),
    memories: List<AiMemory> = emptyList(),
) {
    val personas: List<AiPersona> = java.util.Collections.unmodifiableList(personas.toList())
    val memories: List<AiMemory> = java.util.Collections.unmodifiableList(memories.toList())
    init {
        require(revision >= 0 && personas.size <= 32 && memories.size <= 128) { "Invalid context library" }
        require(personas.map { it.id }.distinct().size == personas.size &&
            memories.map { it.id }.distinct().size == memories.size) { "Duplicate context identity" }
        require(memories.all { memory -> memory.personaId == null || personas.any { it.id == memory.personaId } }) {
            "Invalid memory scope"
        }
    }

    fun select(personaId: String?, memoryIds: Set<String>): AiWorkspaceContext {
        val persona = personaId?.let { id -> personas.singleOrNull { it.id == id } ?: error("Context expired") }
        val selected = memories.filter { it.id in memoryIds }
        require(selected.size == memoryIds.size) { "Context expired" }
        return AiWorkspaceContext(persona, selected)
    }

    override fun toString() = "AiContextLibrary(redacted)"
}

/** Immutable network projection; created only from an explicit reviewed selection. */
class AiWorkspaceContext(val persona: AiPersona? = null, memories: List<AiMemory> = emptyList(), skills: List<AiSkill> = emptyList()) {
    val memories: List<AiMemory> = java.util.Collections.unmodifiableList(memories.toList())
    val skills: List<AiSkill> = java.util.Collections.unmodifiableList(skills.toList())
    val characterCount: Int get() = (persona?.let { it.name.length + it.description.length + it.instructions.length } ?: 0) +
        memories.sumOf { it.text.length } + skills.sumOf { it.characterCount }
    init {
        require(memories.size <= 128 && memories.map { it.id }.distinct().size == memories.size) { "Invalid memory selection" }
        require(memories.all { it.personaId == null || it.personaId == persona?.id }) { "Memory scope mismatch" }
        require(skills.size <= 32 && skills.map { it.id }.distinct().size == skills.size) { "Invalid Skill selection" }
        require(characterCount <= AiReference.MAX_CONTEXT_CHARS) { "Context budget exceeded" }
    }
    fun validate(history: List<AiMessage>, references: List<AiReference>, tools: Set<String> = emptySet()) {
        require(skills.all { it.supportedBy(tools) }) { "Skill tools unavailable" }
        AiReference.validate(history, references)
        require(characterCount + history.sumOf { it.content.length } + references.sumOf { it.text.length } <=
            AiReference.MAX_CONTEXT_CHARS) { "Context budget exceeded" }
    }
    override fun toString() = "AiWorkspaceContext(redacted)"
}

private fun contextText(value: String, limit: Int, empty: Boolean = false) {
    require((empty || value.isNotBlank()) && value.length <= limit &&
        value.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid context text" }
}
