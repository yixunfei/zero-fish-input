package dev.zeroinput.ime.ui

import org.junit.Assert.*
import org.junit.Test

class SymbolPagesTest {
    @Test fun everyCuratedSymbolIsReachableThroughItsGroupAndBoundedPage() {
        assertEquals(12, SymbolPages.groups.size)
        assertTrue(SymbolPages.groups.sumOf { it.symbols.size } >= 1300)
        SymbolPages.groups.forEachIndexed { index, group ->
            val pages = SymbolPages.entries.filter { it.group == index }
            assertEquals(group.symbols, pages.flatMap { it.symbols })
            assertEquals(group.symbols.distinct(), group.symbols)
            assertEquals(index, SymbolPages.entries[SymbolPages.firstPage(index)].group)
            pages.forEach { assertTrue(it.symbols.size in 1..27) }
            group.symbols.forEach { symbol ->
                assertFalse(symbol.any { it.isISOControl() || it.isWhitespace() || it.category == CharCategory.PRIVATE_USE })
            }
        }
    }
}
