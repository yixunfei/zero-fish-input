package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.*
import org.junit.Assert.*
import org.junit.Test

class ModelRankingTest {
    private object NoOpPersonalizationStore : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
    private val candidates = listOf("知识", "指示", "芝士").mapIndexed { index, text ->
        Candidate("c$index", text, input = "zhishi")
    }
    private fun state() = InputSessionState(
        privacy = SessionPrivacy(false, true, true, PrivacyReason.NONE),
        snapshot = EngineSnapshot("zhishi", "zhi shi", candidates),
    )

    @Test fun `frozen confidence threshold preserves ties and rejects nonfinite scores`() {
        assertEquals(2, ModelRankingPolicy.winner(floatArrayOf(-4f, -3f, -1.75f)))
        assertNull(ModelRankingPolicy.winner(floatArrayOf(-4f, -3f, -1.751f)))
        assertNull(ModelRankingPolicy.winner(floatArrayOf(1f, 1f)))
        assertNull(ModelRankingPolicy.winner(floatArrayOf(Float.NaN, 2f)))
        assertNull(ModelRankingPolicy.winner(floatArrayOf(1f, Float.POSITIVE_INFINITY)))
    }

    @Test fun `private editors and explicit user choices cannot be reranked`() {
        val normal = state()
        val snapshots = listOf(
            normal.snapshot.copy(canUndoSelection = true), normal.snapshot.copy(hasPreviousPage = true),
            normal.snapshot.copy(highlightedIndex = 1), normal.snapshot.copy(rawInput = "zhi'shi"),
            normal.snapshot.copy(candidates = candidates.map { it.copy(input = "zhi") }),
            normal.snapshot.copy(candidates = candidates.map { it.copy(kind = CandidateKind.RELATED_READING) }),
            normal.snapshot.copy(candidates = candidates + Candidate("personal:1", "芝士")),
            normal.snapshot.copy(candidates = candidates.map { it.copy(text = "很好吃") }),
        )
        for (snapshot in snapshots) assertTrue(ModelRankingPolicy.eligible(normal.copy(snapshot = snapshot)).isEmpty())
        assertTrue(ModelRankingPolicy.eligible(normal.copy(language = InputLanguage.ENGLISH)).isEmpty())
        assertTrue(ModelRankingPolicy.eligible(normal.copy(languagePackKey = "custom")).isEmpty())
        assertTrue(ModelRankingPolicy.eligible(normal.copy(modelRanked = true)).isEmpty())
        for (reason in PrivacyReason.entries.filter { it != PrivacyReason.NONE }) {
            val restricted = normal.copy(privacy = SessionPrivacy(false, true, false, reason))
            assertTrue(ModelRankingPolicy.eligible(restricted).isEmpty())
        }
        assertEquals(candidates, ModelRankingPolicy.eligible(normal))
    }

    @Test fun `short words remain within original first eight and first choice must be eligible`() {
        val many = (0..20).map { Candidate("$it", "知识", input = "zhishi") }
        assertEquals(8, ModelRankingPolicy.eligible(state().copy(snapshot = state().snapshot.copy(candidates = many))).size)
        assertTrue(ModelRankingPolicy.eligible(state().copy(snapshot = state().snapshot.copy(
            candidates = listOf(Candidate("long", "知识库", input = "zhishi")) + candidates))).isEmpty())
    }

    @Test fun `tap space and enter route to the promoted native candidate`() {
        for (command in listOf(InputCommand.SelectCandidate(0), InputCommand.Space, InputCommand.Enter)) {
            val editor = Editor()
            val engine = Engine(state().snapshot)
            val controller = InputSessionController(editor, { engine }, NoOpPersonalizationStore)
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
            val revision = controller.state.candidateRevision
            assertTrue(controller.applyModelWinner(revision, 2))
            assertFalse(controller.applyModelWinner(revision, 1))
            assertEquals(listOf("芝士", "知识", "指示"), controller.state.snapshot.candidates.map { it.text })
            controller.handle(command)
            assertEquals("芝士", editor.committed)
            assertEquals(2, engine.selected)
        }
    }

    @Test fun `new composition privacy and page changes invalidate a model result`() {
        val controller = InputSessionController(Editor(), { Engine(state().snapshot) }, NoOpPersonalizationStore)
        controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
        val revision = controller.state.candidateRevision
        controller.handle(InputCommand.Text("x"))
        assertFalse(controller.applyModelWinner(revision, 1))
        controller.updatePrivacy(PrivacyConfiguration(incognitoMode = true))
        assertTrue(controller.modelCandidates().isEmpty())
        controller.close()
        assertFalse(controller.applyModelWinner(controller.state.candidateRevision, 1))
    }

    @Test fun `literal punctuation commits the visible model winner before punctuation`() {
        val editor = Editor()
        val engine = Engine(state().snapshot)
        val controller = InputSessionController(editor, { engine }, NoOpPersonalizationStore)
        controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
        assertTrue(controller.applyModelWinner(controller.state.candidateRevision, 2))
        controller.handle(InputCommand.LiteralText("。"))
        assertEquals("芝士。", editor.committed)
        assertEquals(2, engine.selected)
    }

    @Test fun `context retains only contiguous recent Chinese commits and wipes on clear`() {
        val context = CommittedModelContext()
        context.append("今天吃了芝士")
        context.append("。现在学习知识")
        assertEquals("现在学习知识", String(context.copy()))
        context.append("学".repeat(100))
        assertEquals("学".repeat(16), String(context.copy()))
        context.clear()
        assertTrue(context.copy().isEmpty())
        context.append("新词a")
        assertTrue(context.copy().isEmpty())
    }

    private class Editor : EditorConnection {
        var committed = ""
        override fun commitText(text: String): Boolean { committed += text; return true }
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }

    private class Engine(override var snapshot: EngineSnapshot) : InputEngine {
        var selected = -1
        override val descriptor = EngineDescriptor("fixture", "fixture", "1", setOf(InputLanguage.CHINESE))
        override fun start(context: EditorContext) = snapshot
        override fun handle(key: EngineKey): EngineUpdate { snapshot = snapshot.copy(rawInput = "new"); return EngineUpdate(snapshot) }
        override fun selectCandidate(index: Int): EngineUpdate {
            selected = index
            val text = snapshot.candidates[index].text
            snapshot = EngineSnapshot.Empty
            return EngineUpdate(snapshot, text)
        }
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() = Unit
    }
}
