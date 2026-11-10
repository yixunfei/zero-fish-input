package dev.zeroinput.ai.api

/** Reviewed declarative text; required tools describe prerequisites, never grants. */
class AiSkill(val id: String, val name: String, val description: String, val instructions: String,
    requiredTools: List<String> = emptyList()) {
    val requiredTools: List<String> = java.util.Collections.unmodifiableList(requiredTools.toList())
    init {
        require(id.isNotBlank() && id.length <= 128 && name.isNotBlank() && name.length <= 80 && description.length <= 512) { "Invalid Skill" }
        val bytes = instructions.toByteArray(Charsets.UTF_8)
        try { require(instructions.isNotBlank() && bytes.size <= MAX_BYTES) { "Invalid Skill instructions" } }
        finally { bytes.fill(0) }
        require(listOf(id, name, description, instructions).all { value -> value.none { it.isISOControl() && it !in "\n\r\t" } }) { "Invalid Skill text" }
        require(requiredTools.size <= 16 && requiredTools.distinct().size == requiredTools.size &&
            requiredTools.all { tool -> tool.isNotEmpty() && tool.length <= 96 && tool.all { it.isLetterOrDigit() || it in "_./-" } }) { "Invalid Skill requirements" }
    }
    val characterCount: Int get() = name.length + description.length + instructions.length
    fun supportedBy(tools: Set<String>) = tools.containsAll(requiredTools)
    override fun toString() = "AiSkill(redacted)"
    companion object { const val MAX_BYTES = 16_384 }
}

class AiSkillLibrary(val revision: Long = 0, skills: List<AiSkill> = emptyList()) {
    val skills: List<AiSkill> = java.util.Collections.unmodifiableList(skills.toList())
    init { require(revision >= 0 && skills.size <= 32 && skills.map { it.id }.distinct().size == skills.size) { "Invalid Skill library" } }
    override fun toString() = "AiSkillLibrary(redacted)"
}
