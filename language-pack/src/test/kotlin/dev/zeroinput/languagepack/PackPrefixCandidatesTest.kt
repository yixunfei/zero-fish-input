package dev.zeroinput.languagepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPrefixCandidatesTest {
    @Test fun `prefix lookup preserves grouped dictionary priority and unique values`() {
        val entries = listOf("nz" to "last alphabetically", "na" to "shared", "nz" to "shared") +
            (0 until 30).map { "nb$it" to "value-$it" }
        val index = PackPrefixCandidates(entries)
        assertEquals(listOf("last alphabetically", "shared") + (0..5).map { "value-$it" }, index.find("n"))
        assertEquals(listOf("shared"), index.find("na"))
        assertTrue(index.find("none").isEmpty())
    }

    @Test fun `maximum length shortcuts retain completions beyond short prefix budgets`() {
        val stem = "x".repeat(58)
        val entries = (0 until 50_000).map { stem + it.toString().padStart(6, '0') to "value-$it" }
        val index = PackPrefixCandidates(entries)
        assertEquals((0..7).map { "value-$it" }, index.find("x"))
        assertEquals(listOf("value-49999"), index.find(stem + "049999"))
        assertEquals((49990..49997).map { "value-$it" }, index.find(stem + "04999"))
    }
}
