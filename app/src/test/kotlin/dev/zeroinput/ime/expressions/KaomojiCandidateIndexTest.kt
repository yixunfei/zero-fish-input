package dev.zeroinput.ime.expressions

import dev.zeroinput.ime.ui.EmojiCategory
import dev.zeroinput.ime.ui.EmojiEntry
import dev.zeroinput.ime.ui.PersonalExpressionsUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaomojiCandidateIndexTest {
    @Test
    fun `public keyword suggestions remain available when personal data is denied`() {
        val index = KaomojiCandidateIndex { error("Personal data must not be read") }

        val suggestions = index.suggestions("nihao", personalAllowed = false)

        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { !it.personal })
        assertEquals(emptyList<String>(), index.suggestions("nihao!", personalAllowed = false))
    }

    @Test
    fun `custom suggestions follow the current permitted snapshot`() {
        var snapshot = PersonalExpressionsUi(custom = listOf(
            EmojiEntry("(o_o)", EmojiCategory.CUSTOM, "nihao", customId = "one"),
        ))
        val index = KaomojiCandidateIndex { snapshot }

        assertTrue(index.suggestions("nihao", personalAllowed = true).any { it.id == "custom:one" })
        assertFalse(index.suggestions("nihao", personalAllowed = false).any { it.personal })
        snapshot = PersonalExpressionsUi()
        assertFalse(index.suggestions("nihao", personalAllowed = true).any { it.personal })
    }
}
