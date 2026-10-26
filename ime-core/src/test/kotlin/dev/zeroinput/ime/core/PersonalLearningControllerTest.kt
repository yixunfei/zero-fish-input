package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.*
import org.junit.Test

class PersonalLearningControllerTest {
    @Test fun `three explicit short code choices change the next candidate order`() {
        val store = Store()
        val controller = controller(store)
        repeat(3) {
            controller.handle(InputCommand.Text("cs"))
            assertEquals("产生", controller.state.snapshot.candidates.first().text)
            val index = controller.state.snapshot.candidates.indexOfFirst { it.text == "测试" }
            controller.handle(InputCommand.SelectCandidate(index))
        }
        controller.handle(InputCommand.Text("cs"))
        assertEquals("测试", controller.state.snapshot.candidates.first().text)
        assertEquals(3, store.count)
    }

    @Test fun `learned native candidate selection preserves the engine learning route`() {
        val store = Store().apply { count = 1 }
        val controller = controller(store)
        controller.handle(InputCommand.Text("cs"))

        val choice = controller.state.snapshot.candidates.indexOfFirst { it.text == "测试" }
        controller.handle(InputCommand.SelectCandidate(choice))

        assertEquals(2, store.count)
        assertEquals("cs", store.lastShortcut)
    }

    @Test fun `promoted native candidate preserves its route and canonical learning input`() {
        val store = Store().apply { count = 3 }
        val controller = InputSessionController(object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String) = true
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }, { CanonicalLearningEngine() }, store).apply {
            start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
        }
        controller.handle(InputCommand.Text("cs"))
        assertEquals("b", controller.state.snapshot.candidates.first().id)
        assertEquals("cs", controller.state.snapshot.candidates.first().input)

        controller.handle(InputCommand.SelectCandidate(0))

        assertEquals("测试", store.lastValue)
        assertEquals("ceshi", store.lastShortcut)
        assertEquals(1, store.writes)
        assertTrue(store.usedIds.isEmpty())
        controller.close()
    }

    @Test fun `learned native partial selection retains the remaining composition`() {
        val store = Store().apply { count = 3 }
        val connection = object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String) = true
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        val controller = InputSessionController(connection, { PartialEngine() }, store).apply {
            start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
        }
        controller.handle(InputCommand.Text("cs"))
        assertEquals("测试", controller.state.snapshot.candidates.first().text)
        controller.handle(InputCommand.SelectCandidate(0))

        assertEquals("ceshi'shijie", controller.state.snapshot.rawInput)
        assertTrue(controller.state.snapshot.canUndoSelection)
        assertEquals("世界", controller.state.snapshot.candidates.single().text)
    }

    @Test fun `implicit commit learning requires opt in and successful editor acceptance`() {
        for (optIn in listOf(false, true)) for (accepted in listOf(false, true)) {
            val store = Store()
            val controller = controller(store, optIn = optIn, accepted = accepted)
            controller.handle(InputCommand.Text("cs"))
            controller.handle(InputCommand.Space)
            assertEquals(if (optIn && accepted) 1 else 0, store.writes)
        }
    }

    @Test fun `personal space use also honors the opt in`() {
        for (optIn in listOf(false, true)) {
            val store = Store().apply { count = 3; personalOnly = true }
            val controller = controller(store, optIn = optIn)
            controller.handle(InputCommand.Text("cs"))
            controller.handle(InputCommand.Space)
            assertEquals(if (optIn) 1 else 0, store.writes)
        }
    }

    @Test fun `enter learns only the literal accepted result without inventing a Chinese word`() {
        val store = Store()
        val controller = controller(store, optIn = true)
        controller.handle(InputCommand.Text("cs"))
        controller.handle(InputCommand.Enter)
        assertEquals(0, store.writes)
        controller.setLanguage(InputLanguage.ENGLISH)
        controller.handle(InputCommand.Text("testing"))
        controller.handle(InputCommand.Enter)
        assertEquals("testing", store.lastValue)
    }

    @Test fun `readiness refresh reorders current input without committing and privacy prevents reads`() {
        val store = Store()
        val controller = controller(store)
        controller.handle(InputCommand.Text("cs"))
        store.count = 3
        controller.refreshPersonalization()
        assertEquals("测试", controller.state.snapshot.candidates.first().text)
        assertEquals("cs", controller.state.snapshot.rawInput)
        assertEquals(0, store.writes)
        controller.updatePrivacy(PrivacyConfiguration(incognitoMode = true))
        val reads = store.reads
        controller.refreshPersonalization()
        assertEquals(reads, store.reads)
        assertTrue(controller.state.snapshot.candidates.isEmpty())
        controller.close()
        controller.refreshPersonalization()
        assertTrue(controller.state.snapshot.candidates.isEmpty())
    }

    @Test fun `sensitive and no learning editors reject explicit and implicit learning`() {
        for (type in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_NULL)) {
            val store = Store()
            val controller = controller(store, optIn = true)
            controller.start(EditorInfo().apply { inputType = type }, InputLanguage.CHINESE, PrivacyConfiguration())
            controller.handle(InputCommand.Text("cs"))
            controller.handle(InputCommand.SelectCandidate(0))
            controller.handle(InputCommand.Space)
            assertEquals(0, store.writes)
        }
    }

    private fun controller(store: Store, optIn: Boolean = false, accepted: Boolean = true): InputSessionController {
        val connection = object : EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String) = accepted
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        return InputSessionController(connection, { Engine() }, store, learnFromCommittedText = { optIn }).apply {
            start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
        }
    }

    private class Store : PersonalizationStore {
        var count = 0
        var personalOnly = false
        var writes = 0
        var reads = 0
        var lastValue = ""
        var lastShortcut = ""
        val usedIds = mutableListOf<String>()
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int): List<PersonalSuggestion> {
            reads++
            if (personalOnly) return emptyList()
            return if (count > 0 && prefix == "cs") listOf(PersonalSuggestion("test", "测试", count, "cs")) else emptyList()
        }
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) {
            if (!learningAllowed) return
            writes++
            lastShortcut = shortcut
            lastValue = value
            if (shortcut == "cs" && value == "测试") count++
        }
        override fun recordUse(id: String, learningAllowed: Boolean) {
            usedIds += id
            if (learningAllowed) { writes++; count++ }
        }
    }

    private class CanonicalLearningEngine : InputEngine {
        override val descriptor = EngineDescriptor("canonical", "canonical", "1", InputLanguage.entries.toSet())
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun handle(key: EngineKey): EngineUpdate {
            snapshot = EngineSnapshot("cs", "cs",
                listOf(Candidate("a", "产生", input = "chansheng"), Candidate("b", "测试", input = "cs")))
            return EngineUpdate(snapshot)
        }
        override fun selectCandidate(index: Int): EngineUpdate {
            val text = snapshot.candidates.getOrNull(index)?.text.orEmpty()
            return EngineUpdate(reset(), text, committedInput = "cs", learningInput = "ceshi")
        }
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() = Unit
    }

    private class PartialEngine : InputEngine {
        override val descriptor = EngineDescriptor("partial", "partial", "1", InputLanguage.entries.toSet())
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun handle(key: EngineKey): EngineUpdate {
            snapshot = EngineSnapshot("cs", "测试世界",
                listOf(Candidate("a", "产生"), Candidate("b", "测试", input = "ceshi")))
            return EngineUpdate(snapshot)
        }
        override fun selectCandidate(index: Int): EngineUpdate {
            val update = EngineUpdate(EngineSnapshot("ceshi'shijie", "测试 shi jie",
                listOf(Candidate("c", "世界", input = "shi'jie")), canUndoSelection = true))
            snapshot = update.snapshot
            return update
        }
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() = Unit
    }

    private class Engine : InputEngine {
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", InputLanguage.entries.toSet())
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> {
                snapshot = EngineSnapshot(key.text, key.text, listOf(Candidate("a", "产生"), Candidate("b", "测试")))
                EngineUpdate(snapshot)
            }
            EngineKey.Space -> selectCandidate(0)
            else -> EngineUpdate(snapshot, consumed = false)
        }
        override fun selectCandidate(index: Int): EngineUpdate {
            val text = snapshot.candidates.getOrNull(index)?.text ?: return EngineUpdate(snapshot, consumed = false)
            val input = snapshot.rawInput
            return EngineUpdate(reset(), text, committedInput = input, learningInput = input)
        }
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() = Unit
    }
}
