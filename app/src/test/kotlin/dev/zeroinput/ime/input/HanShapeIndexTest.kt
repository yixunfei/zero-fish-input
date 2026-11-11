package dev.zeroinput.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HanShapeIndexTest {
    private val index = HanShapeIndex(
        listOf(
            "青\tqīng,jīng\t828\td\t龶月\t",
            "清\tqīng\t819\ta\t氵青\t龶月",
            "晴\tqíng\t61\ta\t日青\t龶月",
            "猜\tcāi\t233\ta\t犭青\t龶月",
            "蒨\tqiàn\t0\td\t艹倩\t青人",
            "海\thǎi\t437\ta\t氵每\t",
            "日\trì\t1840\tc\t\t",
            "綠\tlǜ\t390\ta\t糹彔\t",
        ).joinToString("\n").reader().buffered(),
    )

    @Test fun directComponentsRankBeforeNestedMatchesAndDisplayTones() {
        val matches = index.similar("青", radical = false)
        assertEquals(listOf("清", "猜", "晴", "蒨"), matches.map { it.character })
        assertEquals("qīng", matches.first().pinyin)
        assertTrue(index.similar("日", radical = true).any { it.character == "晴" })
    }

    @Test fun knownCharacterPartsAndRadicalVariantsWorkAcrossForms() {
        assertTrue(index.similar("清", radical = false).any { it.character == "晴" })
        assertEquals(index.similar("氵", radical = true), index.similar("水", radical = true))
        assertTrue(index.similar("水", radical = true).any { it.character == "海" })
        assertTrue(index.similar("不存在", radical = false).isEmpty())
    }

    @Test fun pinyinSeedsIncludePolyphonesAndToneMarkedUmlaut() {
        assertEquals("青", index.seeds("jing").single().character)
        assertEquals("綠", index.seeds("lv").single().character)
        assertTrue(index.seeds("qing").any { it.character == "青" })
        assertTrue(index.seeds("qing!").isEmpty())
    }
}
