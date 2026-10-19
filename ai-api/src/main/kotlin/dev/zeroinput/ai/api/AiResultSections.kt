package dev.zeroinput.ai.api

/** A bounded, plain-text section extracted from an AI response. */
data class AiResultSection(val index: Int, val text: String)

/** Splits common provider formatting without interpreting or executing markup. */
object AiResultSections {
    const val MAX_SECTIONS = 128

    fun split(value: String): List<AiResultSection> {
        require(value.length <= AiLimits.MAX_OUTPUT_CHARS) { "AI result is too large" }
        if (value.isBlank()) return emptyList()
        val sections = SectionAccumulator()
        val paragraph = StringBuilder()
        val lines = value.split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val content = line.removeSuffix("\r")
            val fence = openingFence(content)
            when {
                content.isBlank() -> flush(paragraph, sections)
                fence != null -> {
                    flush(paragraph, sections)
                    val code = StringBuilder(line)
                    index++
                    while (index < lines.size) {
                        code.append('\n').append(lines[index])
                        if (isClosingFence(lines[index].removeSuffix("\r"), fence)) break
                        index++
                    }
                    sections.add(code.toString())
                }
                isStandaloneSection(content) -> {
                    flush(paragraph, sections)
                    sections.add(line)
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
            index++
        }
        flush(paragraph, sections)
        if (sections.isEmpty) sections.add(value)
        return sections.finish().mapIndexed { sectionIndex, text -> AiResultSection(sectionIndex, text) }
    }

    /** All selected returns the exact original response; subsets retain provider order. */
    fun select(value: String, indices: List<Int>): String? {
        val sections = split(value)
        val selected = indices.distinct().sorted()
        if (selected.isEmpty() || selected.any { it !in sections.indices }) return null
        if (selected.size == sections.size) return value
        return selected.joinToString("\n\n") { sections[it].text.trim() }.trim().takeIf(String::isNotBlank)
    }

    private fun flush(paragraph: StringBuilder, sections: SectionAccumulator) {
        if (paragraph.isEmpty()) return
        sections.add(paragraph.toString())
        paragraph.setLength(0)
    }

    private class SectionAccumulator {
        private val sections = ArrayList<String>(MAX_SECTIONS)
        private val overflow = StringBuilder()
        private var hasOverflow = false

        val isEmpty: Boolean get() = sections.isEmpty() && !hasOverflow

        fun add(value: String) {
            if (value.isBlank()) return
            if (sections.size < MAX_SECTIONS - 1) {
                sections += value
                return
            }
            if (hasOverflow) overflow.append("\n\n")
            overflow.append(value)
            hasOverflow = true
        }

        fun finish(): List<String> = if (hasOverflow) {
            sections + overflow.toString()
        } else {
            sections
        }
    }

    private fun openingFence(line: String): String? {
        val indentation = line.takeWhile { it == ' ' }.length
        if (indentation > 3) return null
        val content = line.drop(indentation)
        val marker = content.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        var length = 0
        while (content.getOrNull(length) == marker) length++
        if (length < 3) return null
        if (marker == '`' && '`' in content.substring(length)) return null
        return marker.toString().repeat(length)
    }

    private fun isClosingFence(line: String, fence: String): Boolean {
        val indentation = line.takeWhile { it == ' ' }.length
        if (indentation > 3 || fence.isEmpty()) return false
        val content = line.drop(indentation)
        val marker = fence.first()
        var length = 0
        while (content.getOrNull(length) == marker) length++
        return length >= fence.length && content.drop(length).all { it == ' ' || it == '\t' }
    }

    private fun isStandaloneSection(line: String): Boolean {
        val value = line.trimStart()
        return value.matches(Regex("#{1,6}\\s+.*")) ||
            value.matches(Regex("(?:[-*+]\\s+|\\d{1,3}[.)]\\s+).*"))
    }
}
