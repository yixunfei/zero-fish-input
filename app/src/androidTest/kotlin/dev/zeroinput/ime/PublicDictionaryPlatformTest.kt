package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PublicDictionaryPlatformTest {
    @Test fun replacementUsesOriginalTableIdentityAndOfflineRestoreDiscardsItsCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            check(graph.publicDictionaries.list().none { it.id == "builtin_jichu" })
            graph.rime.close()
            try {
                graph.publicDictionaries.install("builtin_jichu", "Public fixture", "WANXIANG", "fixture",
                    "---\nname: fixture\n...\n公开词库测试\tgong kai ci ku ce shi\t2000000000\n".byteInputStream(),
                    dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser())
                graph.rime.runtime.prepareDictionariesIfIdle()
                assertTrue(graph.rime.runtime.initializationError?.toString(), graph.rime.runtime.isReady)
                requireNotNull(graph.rime.createNativeOrNull()).use { engine ->
                    engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    "gongkaicikuceshi".forEach { engine.handle(EngineKey.Character(it.toString())) }
                    assertTrue(engine.snapshot.candidates.any { it.text == "公开词库测试" })
                }
            } finally {
                graph.rime.close()
                graph.builtInDictionaries.restore("jichu")
                graph.rime.runtime.prepareDictionariesIfIdle()
                assertTrue(graph.rime.runtime.isReady)
                assertFalse(graph.publicDictionaries.list().any { it.id == "builtin_jichu" })
            }
        }.get(600, TimeUnit.SECONDS)
    }

    @Test fun optionalBuiltInTablesCanBeRemovedAndRestoredWhileBaseConversionRemainsAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            val prior = graph.builtInDictionaries.list()
            check(prior.all { it.enabled && it.replacement == null }) { "Requires default test dictionary selection" }
            graph.rime.close()
            try {
                prior.filterNot { it.required }.forEach { graph.builtInDictionaries.remove(it.table) }
                graph.rime.runtime.prepareDictionariesIfIdle()
                assertTrue(graph.rime.runtime.initializationError?.toString(), graph.rime.runtime.isReady)
                requireNotNull(graph.rime.createNativeOrNull()).use { engine ->
                    engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    "ni".forEach { engine.handle(EngineKey.Character(it.toString())) }
                    val index = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
                    assertTrue(index >= 0)
                    assertEquals("你", engine.selectCandidate(index).committedText)
                }
                val root = java.io.File(context.noBackupFilesDir, "rime")
                val generation = java.io.File(root, "verified-generation").readText()
                assertFalse(java.io.File(root, "shared-$generation/dicts/jichu.dict.yaml").exists())
            } finally {
                graph.rime.close()
                graph.builtInDictionaries.restoreAll()
                graph.rime.runtime.prepareDictionariesIfIdle()
                assertTrue(graph.rime.runtime.isReady)
            }
        }.get(600, TimeUnit.SECONDS)
    }

    @Test fun installedPublicWordsEnterNativeCandidatesAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            graph.rime.close()
            try {
                val text = "---\nname: fixture\n...\n公开词库测试\tgong kai ci ku ce shi\t2000000000\n"
                graph.publicDictionaries.install("native_fixture", "Public test", "ICE", "fixture",
                    text.byteInputStream(), dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser())
                graph.rime.runtime.prepareDictionariesIfIdle()
                assertTrue(graph.rime.runtime.isReady)
                val engine = requireNotNull(graph.rime.createNativeOrNull(ChineseInputOptions()))
                engine.use {
                    it.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    "gongkaicikuceshi".forEach { char -> it.handle(EngineKey.Character(char.toString())) }
                    assertTrue(it.snapshot.candidates.any { candidate -> candidate.text == "公开词库测试" })
                }
            } finally {
                graph.rime.close()
                graph.publicDictionaries.remove("native_fixture")
                graph.rime.runtime.prepareDictionariesIfIdle()
            }
        }.get(600, TimeUnit.SECONDS)
    }

    @Test fun fullBundledDictionaryAndGrammarConvertAcrossLayouts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ZeroInputApplication).graph
        graph.engineExecutor.submit {
            graph.rime.close()
            val ready = graph.rime.warmUp()
            // Initialization errors contain only static diagnostics/public asset paths.
            assertTrue(graph.rime.runtime.initializationError?.toString(), ready)
            for ((options, code) in listOf(
                ChineseInputOptions() to "nihao",
                ChineseInputOptions(keyboardLayout = ChineseKeyboardLayout.NINE_KEY) to "64426",
                ChineseInputOptions(doublePinyinScheme = DoublePinyinScheme.MICROSOFT) to "nihk",
                ChineseInputOptions(doublePinyinScheme = DoublePinyinScheme.ZIRANMA) to "nihk",
            )) {
                val engine = graph.rime.createNativeOrNull(options)
                assertNotNull("${options.keyboardLayout}/${options.doublePinyinScheme}: ${graph.rime.runtime.initializationError}", engine)
                requireNotNull(engine).use {
                    it.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                    code.forEach { char -> it.handle(EngineKey.Character(char.toString())) }
                    val index = it.snapshot.candidates.indexOfFirst { candidate -> candidate.text == "你好" }
                    assertTrue(index >= 0)
                    assertEquals("你好", it.selectCandidate(index).committedText)
                }
            }
        }.get(600, TimeUnit.SECONDS)
    }
}
