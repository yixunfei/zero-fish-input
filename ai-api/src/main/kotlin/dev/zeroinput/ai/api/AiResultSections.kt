package dev.zeroinput.ai.api

/** A bounded, plain-text section extracted from an AI response. */
data class AiResultSection(val index: Int, val text: String)

/** Splits common provider formatting without interpreting or executing markup. */
object AiResultSections {
    const val MAX_SECTIONS = 128

    fun split(value: String): List<AiResultSection> = spans(value).mapIndexed { index, range ->
        AiResultSection(index, value.substring(range))
    }

    /** Each selected span keeps its original whitespace and preceding section separator. */
    fun select(value: String, indices: List<Int>): String? {
        val sections = spans(value)
        val selected = indices.toSet()
        if (selected.isEmpty() || selected.any { it !in sections.indices }) return null
        if (selected.size == sections.size) return value
        return buildString {
            for (i in sections.indices) {
                if (i !in selected) continue
                val span = sections[i]
                if (isNotEmpty()) append(value, sections[i - 1].last + 1, span.first)
                append(value, span.first, span.last + 1)
            }
        }
    }

    private fun spans(value: String): List<IntRange> {
        require(value.length <= AiLimits.MAX_OUTPUT_CHARS) { "AI result is too large" }
        val sections = ArrayList<IntRange>()
        var start = 0
        var previousEnd = 0
        var blockStart = -1
        var fence: String? = null
        fun finishBlock(end: Int) {
            if (blockStart < 0) return
            if (sections.size < MAX_SECTIONS) sections.add(blockStart until end)
            else sections[sections.lastIndex] = sections.last().first until end
            blockStart = -1
        }
        while (start < value.length) {
            val newline = value.indexOf('\n', start).let { if (it < 0) value.length else it }
            val end = if (newline < value.length && newline > start && value[newline - 1] == '\r') newline - 1 else newline
            val line = value.substring(start, end)
            if (fence != null) {
                if (isClosingFence(line, fence)) {
                    finishBlock(end)
                    fence = null
                }
            } else if (line.isBlank()) {
                finishBlock(previousEnd)
            } else {
                fence = openingFence(line)
                val standalone = fence == null && isStandaloneSection(line)
                if (fence != null || standalone) finishBlock(previousEnd)
                if (blockStart < 0) blockStart = start
                if (standalone) finishBlock(end)
            }
            previousEnd = end
            start = newline + 1
        }
        finishBlock(previousEnd)
        return sections
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

    private val heading = Regex("#{1,6}\\s+.*")
    private val listItem = Regex("(?:[-*+]\\s+|\\d{1,3}[.)]\\s+).*")

    private fun isStandaloneSection(line: String): Boolean {
        val value = line.trimStart()
        return heading.matches(value) || listItem.matches(value)
    }
}
