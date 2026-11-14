package dev.zeroinput.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.personalization.QueuedPersonalizationStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.UserLexiconRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Public fixture through real Rime and the production asynchronous learning adapter. */
@RunWith(AndroidJUnit4::class)
class PersonalLearningRimeTest {
    @Test fun exhaustedRelatedReadingsKeepTheLastPrimaryCandidatesSelectable() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val engine = graph.engineExecutor.submit<InputEngine> {
            checkNotNull(graph.rime.createNativeOrNull(ChineseInputOptions()))
        }.get(300, TimeUnit.SECONDS)
        try {
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, "fixture"))
            for (key in "aa") engine.handle(EngineKey.Character(key.toString()))
            repeat(32) {
                if (engine.snapshot.hasNextPage) {
                    val before = engine.snapshot
                    val update = engine.changePage(PageDirection.NEXT)
                    if (!update.consumed) {
                        graph.engineExecutor.submit {}.get(20, TimeUnit.SECONDS)
                        val exhausted = engine.changePage(PageDirection.NEXT)
                        assertFalse(exhausted.consumed)
                        assertFalse(exhausted.snapshot.hasNextPage)
                        assertEquals(before.candidates, exhausted.snapshot.candidates)
                        assertTrue(exhausted.snapshot.candidates.isNotEmpty())
                        val selected = engine.selectCandidate(0)
                        assertTrue(selected.consumed)
                        assertTrue(selected.committedText == before.candidates.first().text ||
                            selected.snapshot.canUndoSelection)
                        return
                    }
                }
            }
            fail("The fixture must reach the last primary candidate page")
        } finally { engine.close() }
    }

    @Test fun repeatedNonDefaultChoicesLearnTheirShortCodeAndSurviveReload() =
        verifyRepeatedChoice(ChineseInputOptions(), "xz", "限制")

    @Test fun nonDefaultSingleCharacterCanMoveFromALaterPageToTheFirstPage() =
        verifyRepeatedChoice(ChineseInputOptions(candidatePageSize = 5), "xian", "限")

    @Test fun nineKeyChoicesRetainTheirLearningCode() =
        verifyRepeatedChoice(ChineseInputOptions(keyboardLayout = ChineseKeyboardLayout.NINE_KEY), "9426944", "限制")

    @Test fun doublePinyinChoicesRetainTheirLearningCode() =
        verifyRepeatedChoice(ChineseInputOptions(doublePinyinScheme = DoublePinyinScheme.MICROSOFT), "xmvi", "限制")

    private fun verifyRepeatedChoice(options: ChineseInputOptions, code: String, word: String) {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val engine = graph.engineExecutor.submit<InputEngine> {
            check(graph.rime.runtime.isReady)
            checkNotNull(graph.rime.createNativeOrNull(options))
        }.get(300, TimeUnit.SECONDS)
        val worker = Executors.newSingleThreadExecutor()
        val disk = MemoryStore()
        val repository = UserLexiconRepository(disk)
        val store = QueuedPersonalizationStore(repository, repository::warmUp, worker)
        var committed = ""
        val connection = object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String): Boolean { committed = text; return true }
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        val controller = InputSessionController(connection, { engine }, store, personalPromotionThreshold = { 3 })
        fun settle() {
            repeat(3) {
                worker.submit {}.get(10, TimeUnit.SECONDS)
                controller.refreshPersonalization()
            }
        }
        fun type() {
            controller.reset()
            code.forEach { controller.handle(InputCommand.Text(it.toString())) }
            settle()
        }
        try {
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
                InputLanguage.CHINESE, PrivacyConfiguration())
            repeat(3) { round ->
                type()
                if (round == 0 && code in listOf("xz", "xian")) {
                    assertNotEquals(word, controller.state.snapshot.candidates.first().text)
                }
                for (page in 0 until 32) {
                    if (controller.state.snapshot.candidates.any { it.text == word }) break
                    controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
                    settle()
                }
                val choice = controller.state.snapshot.candidates.indexOfFirst { it.text == word }
                assertTrue("Non-default fixture candidate must be reachable", choice >= 0)
                controller.handle(InputCommand.SelectCandidate(choice))
                assertEquals(word, committed)
                settle()
                assertEquals(round + 1, worker.submit<Int> {
                    repository.frequenciesFor(listOf(word), InputLanguage.CHINESE)[word] ?: 0
                }.get(10, TimeUnit.SECONDS))
            }
            type()
            assertEquals(word, controller.state.snapshot.candidates.first().text)
            controller.handle(InputCommand.Space)
            assertEquals(word, committed)
            assertEquals(3, worker.submit<Int> {
                UserLexiconRepository(disk).frequenciesFor(listOf(word), InputLanguage.CHINESE)[word] ?: 0
            }.get(10, TimeUnit.SECONDS))
        } finally {
            controller.close()
            store.close()
            disk.delete(true)
        }
    }

    @Test fun repeatedFullPinyinChoicesIncreaseFrequencyAndPromoteBothFullAndInitialInput() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val engine = graph.engineExecutor.submit<InputEngine> {
            check(graph.rime.runtime.isReady)
            checkNotNull(graph.rime.createNativeOrNull(ChineseInputOptions()))
        }.get(300, TimeUnit.SECONDS)
        val worker = Executors.newSingleThreadExecutor()
        val disk = MemoryStore()
        val repository = UserLexiconRepository(disk)
        val store = QueuedPersonalizationStore(repository, repository::warmUp, worker)
        var committed = ""
        val connection = object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String): Boolean { committed = text; return true }
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        var threshold = 3
        val controller = InputSessionController(connection, { engine }, store, personalPromotionThreshold = { threshold })
        fun settle() {
            repeat(3) {
                worker.submit {}.get(10, TimeUnit.SECONDS)
                controller.refreshPersonalization()
            }
        }
        fun type(code: String) {
            controller.reset()
            code.forEach { controller.handle(InputCommand.Text(it.toString())) }
            settle()
        }
        try {
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
                InputLanguage.CHINESE, PrivacyConfiguration())
            type("cs")
            val originalFirst = controller.state.snapshot.candidates.first().text
            repeat(3) { index ->
                type("ceshi")
                val choice = controller.state.snapshot.candidates.indexOfFirst { it.text == "测试" }
                assertTrue("Fixture candidate must be available", choice >= 0)
                controller.handle(InputCommand.SelectCandidate(choice))
                assertEquals("测试", committed)
                settle()
                assertEquals(index + 1, worker.submit<Int> {
                    repository.frequenciesFor(listOf("测试"), InputLanguage.CHINESE)["测试"] ?: 0
                }.get(10, TimeUnit.SECONDS))
            }
            for (code in listOf("ceshi", "cs")) {
                type(code)
                assertEquals("测试", controller.state.snapshot.candidates.first().text)
            }
            threshold = 4
            controller.refreshPersonalization()
            assertEquals(originalFirst, controller.state.snapshot.candidates.first().text)
            threshold = 3
            controller.refreshPersonalization()
            controller.handle(InputCommand.SelectCandidate(0))
            settle()
            assertEquals(4, worker.submit<Int> {
                UserLexiconRepository(disk).frequenciesFor(listOf("测试"), InputLanguage.CHINESE)["测试"] ?: 0
            }.get(10, TimeUnit.SECONDS))

            worker.submit { repository.addPhrase("nihao", "你", InputLanguage.CHINESE, 3) }
                .get(10, TimeUnit.SECONDS)
            type("nihao")
            controller.handle(InputCommand.SelectSyllable)
            assertEquals("你", controller.state.snapshot.candidates.first().text)
            controller.handle(InputCommand.SelectCandidate(0))
            assertTrue("Segment state: composing=${controller.state.snapshot.isComposing} " +
                "undo=${controller.state.snapshot.canUndoSelection} count=${controller.state.snapshot.candidates.size}",
                controller.state.snapshot.canUndoSelection)
        } finally {
            controller.close()
            store.close()
            disk.delete(true)
        }
    }

    private class MemoryStore : EncryptedStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes?.fill(0); bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { bytes?.fill(0); bytes = null }
    }
}
