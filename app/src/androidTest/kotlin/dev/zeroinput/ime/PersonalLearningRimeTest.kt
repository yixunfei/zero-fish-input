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
