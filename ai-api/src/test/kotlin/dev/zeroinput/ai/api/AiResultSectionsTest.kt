package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiResultSectionsTest {
    @Test fun splitsParagraphsHeadingsAndListItemsInSourceOrder() {
        val sections = AiResultSections.split("Intro.\n\n## Steps\n- first\n- second\n1. third")

        assertEquals(listOf("Intro.", "## Steps", "- first", "- second", "1. third"), sections.map { it.text })
        assertEquals(sections.indices.toList(), sections.map { it.index })
    }

    @Test fun treatsFencedCodeAsOneSectionAndRoundTripsWhenEverythingIsSelected() {
        val response = "Summary\n\n```kotlin\nval answer = 42\n```\n\nTail"
        val sections = AiResultSections.split(response)

        assertEquals(listOf("Summary", "```kotlin\nval answer = 42\n```", "Tail"), sections.map { it.text })
        assertEquals(response, AiResultSections.select(response, sections.indices.toList()))
    }

    @Test fun fenceWithTrailingTextDoesNotCloseTheCodeSection() {
        val response = "```text\nbefore\n```still code\nafter\n``` \n\nTail"

        assertEquals(
            listOf("```text\nbefore\n```still code\nafter\n``` ", "Tail"),
            AiResultSections.split(response).map { it.text },
        )
    }

    @Test fun selectedSectionsAreOrderedDeduplicatedAndTrimmed() {
        val response = "Keep this\n\nRemove this\n\nKeep this too"

        assertEquals("Keep this\n\nKeep this too", AiResultSections.select(response, listOf(2, 0, 2)))
        assertNull(AiResultSections.select(response, emptyList()))
        assertNull(AiResultSections.select(response, listOf(99)))
    }

    @Test fun capsSectionCountWithoutDroppingResponseText() {
        val response = (0..AiResultSections.MAX_SECTIONS).joinToString("\n\n") { "part $it" }

        val sections = AiResultSections.split(response)

        assertEquals(AiResultSections.MAX_SECTIONS, sections.size)
        assertTrue(sections.last().text.contains("part ${AiResultSections.MAX_SECTIONS}"))
        assertEquals(response, AiResultSections.select(response, sections.indices.toList()))
    }

    @Test fun maximumSizeResponseWithManySectionsKeepsOverflow() {
        val response = List(AiLimits.MAX_OUTPUT_CHARS / 4) { "# x" }.joinToString("\n")

        val sections = AiResultSections.split(response)

        assertEquals(AiResultSections.MAX_SECTIONS, sections.size)
        val overflowSections = AiLimits.MAX_OUTPUT_CHARS / 4 - AiResultSections.MAX_SECTIONS + 1
        assertEquals(List(overflowSections) { "# x" }.joinToString("\n\n"), sections.last().text)
        assertEquals(response, AiResultSections.select(response, sections.indices.toList()))
    }
}
