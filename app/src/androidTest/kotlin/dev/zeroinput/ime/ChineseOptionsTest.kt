package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.CandidateTextNormalizer
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.FuzzyPinyinPair
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChineseOptionsTest {
    @Test
    fun configurationDeploymentDoesNotInvalidateALiveEngineAndReleasesUnusedIndexes() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            checkNotNull(graph.rime.createNativeOrNull()).use { active ->
                active.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                val replacement = graph.rime.createNativeOrNull(ChineseInputOptions(abbreviatedPinyin = false))
                replacement?.close()
                assertTrue("Deployment must defer while the current engine is alive", replacement == null)
                assertTrue("Deferring deployment must preserve runtime availability", graph.rime.runtime.isReady)
                assertTrue("The original engine must still accept input", active.handle(EngineKey.Character("n")).snapshot.isComposing)
            }
            checkNotNull(graph.rime.createNativeOrNull(
                ChineseInputOptions(fuzzyPinyinMask = ChineseInputOptions.MAX_FUZZY_PINYIN_MASK),
            )).close()
            checkNotNull(graph.rime.createNativeOrNull()).close()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = java.io.File(context.noBackupFilesDir, "rime/user")
            val variants = listOf(root, java.io.File(root, "build")).flatMap { it.listFiles().orEmpty().asList() }
            assertTrue("Inactive configuration indexes must not accumulate", variants.none { it.name.startsWith("zeroinput_pinyin_") })
        }.get(60, TimeUnit.SECONDS)
    }

    @Test
    fun scriptSwitchAppliesToCandidatesCommitsAndPersonalText() {
        for ((script, expected) in listOf(ChineseScript.SIMPLIFIED to "中国", ChineseScript.TRADITIONAL to "中國")) {
            withEngine(ChineseInputOptions(script = script)) { engine ->
                type(engine, "zhongguo")
                val index = engine.snapshot.candidates.indexOfFirst { it.text == expected }
                assertTrue("Configured script must appear in candidates", index >= 0)
                assertTrue("Selected output must match the visible candidate", engine.selectCandidate(index).committedText == expected)
                val normalizer = engine as CandidateTextNormalizer
                assertTrue("Personal suggestions must use the same script", normalizer.normalizeCandidateText("中國") == expected)
                assertTrue("Personal suggestions must use the same script", normalizer.normalizeCandidateText("中国") == expected)
            }
        }
    }

    @Test
    fun abbreviationsSupportInitialsAndMixedFullSyllables() {
        withEngine(ChineseInputOptions()) { engine ->
            for (input in listOf("zg", "zhg", "zhongg", "zguo")) {
                engine.reset()
                type(engine, input)
                assertTrue("Abbreviated fixtures must offer the full phrase", offers(engine, "中国"))
            }
        }
        withEngine(ChineseInputOptions(abbreviatedPinyin = false)) { engine ->
            type(engine, "zg")
            assertFalse("Disabled abbreviations must not match the fixture", offers(engine, "中国"))
        }
    }

    @Test
    fun everyFuzzyPairCanBeEnabledIndependently() {
        val fixtures = listOf(
            Triple(FuzzyPinyinPair.Z_ZH, "zongguo", "中国"),
            Triple(FuzzyPinyinPair.C_CH, "cifan", "吃饭"),
            Triple(FuzzyPinyinPair.S_SH, "sijie", "世界"),
            Triple(FuzzyPinyinPair.N_L, "lihao", "你好"),
            Triple(FuzzyPinyinPair.HU_FU, "hujian", "福建"),
            Triple(FuzzyPinyinPair.AN_ANG, "shanhai", "上海"),
            Triple(FuzzyPinyinPair.EN_ENG, "chengong", "成功"),
            Triple(FuzzyPinyinPair.IN_ING, "mintian", "明天"),
            Triple(FuzzyPinyinPair.R_L, "len", "人"),
            Triple(FuzzyPinyinPair.H_F, "huwu", "服务"),
            Triple(FuzzyPinyinPair.IAN_IANG, "xian", "想"),
            Triple(FuzzyPinyinPair.UAN_UANG, "huan", "黄"),
            Triple(FuzzyPinyinPair.ON_ONG, "gon", "公"),
        )
        for ((pair, input, expected) in fixtures) {
            withEngine(ChineseInputOptions().withFuzzy(pair, true)) { engine ->
                type(engine, input)
                assertTrue("Enabled fuzzy pair must match its public fixture: $pair", offers(engine, expected))
            }
        }
        withEngine(ChineseInputOptions()) { engine ->
            type(engine, "zongguo")
            assertFalse("Disabled fuzzy pair must not survive a configuration switch", offers(engine, "中国"))
        }
    }

    @Test
    fun punctuationAndPageSizeFollowConfiguration() {
        for (size in ChineseInputOptions.PAGE_SIZES) {
            withEngine(ChineseInputOptions(candidatePageSize = size, chinesePunctuation = false)) { engine ->
                type(engine, "ni")
                assertEquals(size, engine.snapshot.candidates.size)
                engine.reset()
                val update = engine.handle(EngineKey.Character(","))
                assertTrue("ASCII punctuation must commit unchanged or be delegated to the editor",
                    update.committedText == "," || (!update.consumed && !update.snapshot.isComposing))
            }
        }
        withEngine(ChineseInputOptions()) { engine ->
            assertTrue("Chinese punctuation must use full-width marks", engine.handle(EngineKey.Character(",")).committedText == "，")
        }
    }

    private fun offers(engine: InputEngine, expected: String): Boolean {
        repeat(10) {
            if (engine.snapshot.candidates.any { candidate -> candidate.text == expected }) return true
            if (!engine.snapshot.hasNextPage) return false
            engine.changePage(PageDirection.NEXT)
        }
        return false
    }

    private fun type(engine: InputEngine, input: String) {
        input.forEach { engine.handle(EngineKey.Character(it.toString())) }
    }

    private fun withEngine(options: ChineseInputOptions, action: (InputEngine) -> Unit) {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            check(graph.rime.runtime.isReady) { "Native runtime must be ready" }
            checkNotNull(graph.rime.createNativeOrNull(options)) { "Configured engine must be available" }.use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                action(engine)
            }
        }.get(60, TimeUnit.SECONDS)
    }
}
