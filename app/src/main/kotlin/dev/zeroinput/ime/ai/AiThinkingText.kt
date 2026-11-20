package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiStreamEvent

/** Compatibility for inline reasoning outside Markdown code and block quotes. */
internal object AiThinkingText {
    data class Split(val answer: String, val thinking: String, val hadThinking: Boolean)
    data class StreamPart(val answer: String, val thinking: String)

    class Stream {
        private val pending = StringBuilder()
        private val literal = LiteralContext()
        private var reasoningDepth = 0
        var hadThinking = false
            private set

        fun append(value: String, final: Boolean = false): StreamPart {
            pending.append(value)
            val answer = StringBuilder()
            val thinking = StringBuilder()
            var cursor = 0
            while (cursor < pending.length) {
                val c = pending[cursor]
                literal.before(c)
                if (c == '<' && !literal.resolveInline(pending, cursor, final)) break
                if (c == '<' && !literal.protected) {
                    val marker = when {
                        pending.startsWith(OPEN, cursor, ignoreCase = true) -> OPEN
                        pending.startsWith(CLOSE, cursor, ignoreCase = true) -> CLOSE
                        else -> null
                    }
                    if (marker != null) {
                        if (marker == OPEN) reasoningDepth++ else if (reasoningDepth > 0) reasoningDepth--
                        hadThinking = true
                        cursor += marker.length
                        literal.consume('<')
                        continue
                    }
                    if (!final && pending.length - cursor < CLOSE.length) {
                        val tail = pending.substring(cursor)
                        if (OPEN.startsWith(tail, true) || CLOSE.startsWith(tail, true)) break
                    }
                }
                (if (reasoningDepth > 0) thinking else answer).append(c)
                literal.consume(c)
                cursor++
            }
            pending.delete(0, cursor)
            return StreamPart(answer.toString(), thinking.toString())
        }

        fun clear() {
            pending.setLength(0)
            literal.reset()
            reasoningDepth = 0
            hadThinking = false
        }
    }

    /** Count delimiter runs across chunks without buffering the code or quoted line. */
    private class LiteralContext {
        private var delimiter = ' '
        private var width = 0
        private var fenced = false
        private var run = ' '
        private var count = 0
        private var runAtStart = false
        private var closingFence = false
        private var lineStart = true
        private var quoted = false
        private var previousWasCr = false
        private var inlineConfirmed = false
        private var scanned = 0
        private var closingTicks = 0
        private var afterNewline = false

        // A stray backtick is ordinary text. Hold ambiguous tags until the matching
        // delimiter is known. scanned is relative to the held '<' across chunk drains.
        fun resolveInline(text: CharSequence, start: Int, final: Boolean): Boolean {
            if (width == 0 || fenced || inlineConfirmed) return true
            while (start + scanned < text.length) {
                val c = text[start + scanned++]
                if (c == '`') {
                    closingTicks++
                } else {
                    if (closingTicks == width) { inlineConfirmed = true; return true }
                    closingTicks = 0
                }
                if (c == '\n' && text.getOrNull(start + scanned - 2) == '\r') continue
                if (c == '\n' || c == '\r') {
                    if (afterNewline) { width = 0; return true }
                    afterNewline = true
                } else if (c != ' ' && c != '\t') afterNewline = false
            }
            if (final && closingTicks == width) inlineConfirmed = true
            else if (final) width = 0
            else return false
            return true
        }

        val protected get() = width != 0 || quoted

        fun before(c: Char) {
            if (count == 0 || c == run) return
            if (width == 0) {
                val fence = count >= 3 && runAtStart
                if (fence || run == '`') {
                    delimiter = run
                    width = count
                    fenced = fence
                    inlineConfirmed = false
                    scanned = 0
                    closingTicks = 0
                    afterNewline = false
                }
            } else if (run == delimiter) {
                if (fenced) closingFence = runAtStart && count >= width
                else if (count == width) width = 0
            }
            count = 0
        }

        fun consume(c: Char) {
            if (closingFence && c != ' ' && c != '\t') {
                if (c == '\n' || c == '\r') width = 0
                closingFence = false
            }
            if (lineStart && c == '>' && width == 0) quoted = true
            if (!quoted && (c == '`' || c == '~')) {
                if (count == 0) { run = c; runAtStart = lineStart }
                count++
            }
            if (c == '\n' && lineStart && !fenced && !previousWasCr) width = 0
            if (c == '\r' && lineStart && !fenced) width = 0
            previousWasCr = c == '\r'
            if (c == '\n' || c == '\r') {
                lineStart = true
                quoted = false
            } else if (c != ' ' && c != '\t') lineStart = false
        }

        fun reset() {
            width = 0
            closingFence = false
            count = 0
            lineStart = true
            quoted = false
            previousWasCr = false
        }
    }

    fun split(value: String): Split {
        val stream = Stream()
        val part = stream.append(value, final = true)
        return Split(part.answer, part.thinking, stream.hadThinking)
    }

    fun completed(event: AiStreamEvent.Completed): AiStreamEvent.Completed? {
        if (event.text.length > AiLimits.MAX_OUTPUT_CHARS || event.thinking.length > AiLimits.MAX_OUTPUT_CHARS) return null
        val split = split(event.text)
        if (split.answer.isBlank() || split.thinking.length > AiLimits.MAX_OUTPUT_CHARS - event.thinking.length) return null
        return event.copy(text = split.answer, thinking = event.thinking + split.thinking)
    }

    fun answer(value: String): String = split(value).answer

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"
}
