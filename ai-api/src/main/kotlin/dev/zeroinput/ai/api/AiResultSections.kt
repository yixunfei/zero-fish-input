package dev.zeroinput.ai.api

/** A bounded, plain-text section extracted from an AI response. */
data class AiResultSection(val index: Int, val text: String)

/** Splits common provider formatting without interpreting or executing markup. */
object AiResultSections {
    const val MAX_SECTIONS = 128

    fun split(value: String): List<AiResultSection> {
        require(value.length <= AiLimits.MAX_OUTPUT_CHARS) { "AI result is too large" }
        if (value.isBlank()) return emptyList()
        val sections = mutableListOf<String>()
        val paragraph = StringBuilder()
        val lines = value.split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val content = line.removeSuffix("\r")
            when {
                content.isBlank() -> flush(paragraph, sections)
                isFence(content) -> {
                    flush(paragraph, sections)
                    val code = StringBuilder(line)
                    val fence = content.trimStart().takeWhile { it == '`' || it == '~' }
                    index++
                    while (index < lines.size) {
                        code.append('\n').append(lines[index])
                        if (isClosingFence(lines[index].removeSuffix("\r"), fence)) break
                        index++
                    }
                    addBounded(sections, code.toString())
                }
                isStandaloneSection(content) -> {
                    flush(paragraph, sections)
                    addBounded(sections, line)
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
            index++
        }
        flush(paragraph, sections)
        if (sections.isEmpty()) addBounded(sections, value)
        return sections.mapIndexed { sectionIndex, text -> AiResultSection(sectionIndex, text) }
    }

    /** All selected returns the exact original response; subsets retain provider order. */
    fun select(value: String, indices: List<Int>): String? {
        val sections = split(value)
        val selected = indices.distinct().sorted()
        if (selected.isEmpty() || selected.any { it !in sections.indices }) return null
        if (selected.size == sections.size) return value
        return selected.joinToString("\n\n") { sections[it].text.trim() }.trim().takeIf(String::isNotBlank)
    }

    private fun flush(paragraph: StringBuilder, sections: MutableList<String>) {
        if (paragraph.isEmpty()) return
        addBounded(sections, paragraph.toString())
        paragraph.setLength(0)
    }

    private fun addBounded(sections: MutableList<String>, value: String) {
        if (value.isBlank()) return
        if (sections.size < MAX_SECTIONS) {
            sections += value
        } else {
            sections[MAX_SECTIONS - 1] = sections[MAX_SECTIONS - 1] + "\n\n" + value
        }
    }

    private fun isFence(line: String): Boolean = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")

    private fun isClosingFence(line: String, fence: String): Boolean =
        fence.isNotEmpty() && line.trimStart().startsWith(fence)

    private fun isStandaloneSection(line: String): Boolean {
        val value = line.trimStart()
        return value.matches(Regex("#{1,6}\\s+.*")) ||
            value.matches(Regex("(?:[-*+]\\s+|\\d{1,3}[.)]\\s+).*"))
    }
}
