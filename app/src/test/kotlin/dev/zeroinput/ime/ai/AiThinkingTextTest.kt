package dev.zeroinput.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiThinkingTextTest {
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
