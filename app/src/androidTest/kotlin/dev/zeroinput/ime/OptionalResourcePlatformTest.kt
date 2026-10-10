package dev.zeroinput.ime

import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.model.OfflineHandwritingRecognizer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class OptionalResourcePlatformTest {
    @Test fun verifiedDownloadsEnableNativeGrammarAndHandwritingThenRemovalKeepsCoreInput() {
        val directory = InstrumentationRegistry.getArguments().getString("resourceFixtureDir")
        assumeTrue("Supply public release ZIP fixtures explicitly", directory != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            val packs = graph.publicResources.bundledCatalog()
            try {
                for (pack in packs) {
                    graph.publicResources.install(pack, File(directory, pack.url.substringAfterLast('/')))
                }
                graph.rime.close()
                graph.rime.runtime.prepareDictionariesIfIdle()
                assertTrue(graph.rime.runtime.initializationError?.toString(), graph.rime.runtime.isReady)
                assertChineseInput(graph)
                val model = requireNotNull(graph.publicResources.acquire("wanxiang-lts"))
                model.use { assertEquals(398309420L, it.files.values.single().bytes) }
                OfflineHandwritingRecognizer(context, graph.publicResources).use { recognizer ->
                    val values = recognizer.recognize(listOf(floatArrayOf(0.15f, 0.5f, 0.85f, 0.5f)))
                    assertTrue(values.isNotEmpty())
                }
                dev.zeroinput.model.RobertaMiniScorer(graph.publicResources).use { scorer ->
                    val scores = scorer.score("我买了一件新".toCharArray(),
                        arrayOf("衣服".toCharArray(), "依附".toCharArray())) { false }
                    assertArrayEquals(floatArrayOf(-2.2595852f, -14.597835f), requireNotNull(scores), .025f)
                    graph.publicResources.remove("mini-int8")
                    assertNull(graph.publicResources.acquire("mini-int8"))
                    assertNotNull(scorer.score("我买了一件新".toCharArray(),
                        arrayOf("衣服".toCharArray(), "依附".toCharArray())) { false })
                }
            } finally {
                packs.forEach { graph.publicResources.remove(it.id) }
                graph.rime.close()
                graph.rime.runtime.prepareDictionariesIfIdle()
            }
            assertTrue(graph.rime.runtime.isReady)
            assertChineseInput(graph)
            assertNull(graph.publicResources.acquire("handwriting"))
            assertNull(graph.publicResources.acquire("mini-int8"))
            assertThrows(IllegalStateException::class.java) {
                dev.zeroinput.model.RobertaMiniScorer(graph.publicResources)
            }
        }.get(600, TimeUnit.SECONDS)
    }

    private fun assertChineseInput(graph: AppGraph) {
        requireNotNull(graph.rime.createNativeOrNull(ChineseInputOptions())).use {
            it.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            "nihao".forEach { character -> it.handle(EngineKey.Character(character.toString())) }
            assertTrue(it.snapshot.candidates.any { candidate -> candidate.text == "你好" })
        }
    }
}
