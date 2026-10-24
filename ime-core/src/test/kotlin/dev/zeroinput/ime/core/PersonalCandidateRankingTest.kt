package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.Candidate
import org.junit.Assert.*
import org.junit.Test

class PersonalCandidateRankingTest {
    private val native = listOf(Candidate("a", "产生"), Candidate("b", "测试"))

    @Test fun `short code stays editorial until threshold then reflects repeated choice`() {
        fun ranked(count: Int) = PersonalCandidateRanking.merge(native,
            listOf(Candidate("personal:b", "测试", score = count, input = "cs")), "cs", 3)
        assertEquals("产生", ranked(2).first().text)
        assertEquals("测试", ranked(3).first().text)
        assertEquals("personal:b", ranked(3).first().id)
    }

    @Test fun `raising threshold and lowering a weight restore the default order`() {
        val personal = listOf(Candidate("personal:b", "测试", score = 3, input = "cs"))
        assertEquals("产生", PersonalCandidateRanking.merge(native, personal, "cs", 4).first().text)
    }

    @Test fun `completion match does not pin above a stronger exact habit`() {
        val personal = listOf(Candidate("personal:c", "测试词", score = 3, input = "csc"),
            Candidate("personal:b", "测试", score = 4, input = "cs"))
        assertEquals(listOf("测试", "产生", "测试词"),
            PersonalCandidateRanking.merge(native, personal, "cs", 3).map { it.text })
    }

    @Test fun `a different repeated choice can overtake the old habit`() {
        val personal = listOf(Candidate("personal:a", "产生", score = 3, input = "cs"),
            Candidate("personal:b", "测试", score = 20, input = "cs"))
        assertEquals("测试", PersonalCandidateRanking.merge(native, personal, "cs", 3).first().text)
    }
}
