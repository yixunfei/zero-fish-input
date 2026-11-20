package dev.zeroinput.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiThinkingTextTest {
    @Test fun fenceWithTrailingContentStaysLiteralAcrossSingleCharacterChunks() {
        val code = "~~~xml\n~~~  still code\n<think>literal</think>\n~~~ \t\n"
        val raw = code + "<think>hidden</think>end"
        val stream = AiThinkingText.Stream()
        assertEquals(code + "end", raw.map { stream.append(it.toString()).answer }.joinToString(""))
        assertEquals(code + "end", AiThinkingText.split(raw).answer)
    }

    @Test fun unpairedBacktickCannotMakeReasoningVisibleOrInsertable() {
        val prefix = "An unmatched ` delimiter "
        val raw = prefix + "<think>private</think>answer"
        val stream = AiThinkingText.Stream()
        val partial = raw.map { stream.append(it.toString()).answer }.joinToString("")
        assertEquals(prefix, partial)
        assertEquals("answer", stream.append("", final = true).answer)
        assertEquals(prefix + "answer", AiThinkingText.split(raw).answer)
        assertEquals("private", AiThinkingText.split(raw).thinking)
    }

    @Test fun inlineDelimiterCannotMatchAcrossAnEmptyLine() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val raw = "An unmatched ` <think>reason</think>$newline $newline`code`"
            assertEquals("An unmatched ` $newline $newline`code`", AiThinkingText.answer(raw))
        }
    }

    @Test fun nestedReasoningStaysHiddenUntilTheOutermostClose() {
        val raw = "<think>a<think>b</think>c</think>done"
        val stream = AiThinkingText.Stream()
        assertEquals("done", raw.map { stream.append(it.toString()).answer }.joinToString(""))
        assertEquals("abc", AiThinkingText.split(raw).thinking)
        assertEquals("", AiThinkingText.answer("<think>a<think>b</think>c"))
    }

    @Test fun literalMarkdownSurvivesEveryStreamBoundary() {
        val literal = "<think>keep</think>"
        val answer = "```xml\n$literal\n```\n`$literal`\n> $literal\n"
        val raw = answer + "<think>reason</think>end"
        val stream = AiThinkingText.Stream()
        assertEquals(answer + "end", raw.map { stream.append(it.toString()).answer }.joinToString(""))
        assertEquals(answer + "end", AiThinkingText.split(raw).answer)
        assertEquals("reason", AiThinkingText.split(raw).thinking)
    }

    @Test fun `large coalesced chunks and partial markers stay separated`() {
        val stream = AiThinkingText.Stream()
        val answer = "a".repeat(16_000)
        val reasoning = "r".repeat(16_000)
        assertEquals(AiThinkingText.StreamPart(answer, ""), stream.append("$answer<TH"))
        assertEquals(AiThinkingText.StreamPart("", reasoning), stream.append("INK>$reasoning</THINK"))
        assertEquals(AiThinkingText.StreamPart("ok<text", ""), stream.append(">ok<text"))
    }

    @Test fun `truncation keeps supplementary characters intact in both directions`() {
        val value = "A\uD83D\uDE42Z"
        assertEquals("A", value.takeWholeCodePoints(2))
        assertEquals("A\uD83D\uDE42", value.takeWholeCodePoints(3))
        assertEquals("Z", value.takeLastWholeCodePoints(2))
        assertEquals("\uD83D\uDE42Z", value.takeLastWholeCodePoints(3))
    }

    @Test fun `stream separates markers even when each marker arrives in pieces`() {
        val stream = AiThinkingText.Stream()
        val answer = StringBuilder()
        val thinking = StringBuilder()
        "前<think>中间过程</think>后".forEach { character ->
            val part = stream.append(character.toString())
            answer.append(part.answer)
            thinking.append(part.thinking)
        }
        assertEquals("前后", answer.toString())
        assertEquals("中间过程", thinking.toString())
    }

    @Test fun `inline thinking is separated from answer`() {
        val split = AiThinkingText.split("<think>reason</think>晚上好")
        assertEquals("晚上好", split.answer)
        assertEquals("reason", split.thinking)
        assertEquals(true, split.hadThinking)
    }

    @Test fun `unclosed thinking is removed from answer`() {
        assertEquals("结果", AiThinkingText.answer("结果<think>内部过程"))
    }

    @Test fun `multiple blocks and case variants preserve ordinary answer text`() {
        val split = AiThinkingText.split("前<THINK>一</THINK>中<think>二</think>后")
        assertEquals("前中后", split.answer)
        assertEquals("一二", split.thinking)
    }

    @Test fun `reasoning only response is not insertable`() {
        assertEquals("", AiThinkingText.answer("<think>只有过程</think>"))
        assertEquals("", AiThinkingText.answer("<think>未闭合"))
        assertEquals("结果", AiThinkingText.answer("结果</think>"))
    }
}
