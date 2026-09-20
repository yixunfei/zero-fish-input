package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CandidateTextNormalizer
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.engine.api.ReadingSelectionEngine
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSessionControllerTest {
    @Test fun `space and enter on a later personal page commit its visible selection`() {
        for (command in listOf(InputCommand.Space, InputCommand.Enter)) {
            val editor = RecordingConnection()
            val store = object : dev.zeroinput.engine.api.PagedPersonalizationStore {
                override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int) =
                    dev.zeroinput.engine.api.PersonalSuggestionPage(
                        (offset until minOf(offset + limit, 20)).map { PersonalSuggestion("$it", "词组$it", 1) }, offset + limit < 20)
                override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = suggestionPage(prefix, language, 0, limit).items
                override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
                override fun recordUse(id: String, learningAllowed: Boolean) = Unit
            }
            val controller = InputSessionController(editor, { TestEngine() }, store)
            controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
            controller.handle(InputCommand.Text("ni"))
            controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
            assertEquals("词组8", controller.state.snapshot.candidates.first().text)
            controller.handle(command)
            assertEquals(listOf("词组8"), editor.commits)
            controller.close()
        }
    }

    @Test
    fun `unknown editor after a normal session clears candidates and never starts another engine`() {
        val connection = ComposingRecordingConnection()
        val personal = RecordingPersonalization()
        var creations = 0
        val controller = InputSessionController(connection, { creations++; TestEngine() }, personal)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("ni"))
        assertTrue(controller.state.snapshot.isComposing)
        controller.start(EditorInfo().apply { inputType = InputType.TYPE_NULL }, InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("x"))
        controller.handle(InputCommand.SelectCandidate(0))
        assertEquals(1, creations)
        assertEquals("", connection.composing)
        assertEquals(listOf("x"), connection.commits)
        assertTrue(controller.state.snapshot.candidates.isEmpty())
        assertTrue(personal.learned.isEmpty())
        assertTrue(personal.usedIds.isEmpty())
    }

    @Test
    fun `literal digit commits composition once and never runs an editor action`() {
        val connection = ComposingRecordingConnection()
        val controller = controller(connection, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("nihao"))
        controller.handle(InputCommand.LiteralText("4"))
        assertEquals(listOf("你好", "4"), connection.commits)
        assertEquals("", connection.composing)
        assertFalse(controller.state.snapshot.isComposing)
        controller.handle(InputCommand.LiteralText("2"))
        assertEquals(listOf("你好", "4", "2"), connection.commits)
    }

    @Test
    fun `literal digit preserves raw text when an engine declines composition commit`() {
        val connection = RecordingConnection()
        val engine = UnconsumingEngine("ni")
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("ni"))
        controller.handle(InputCommand.LiteralText("4"))
        assertEquals(listOf("ni", "4"), connection.commits)
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `reading selection uses optional port and is rejected after privacy tightening`() {
        var readingCalls = 0
        val engine = object : InputEngine by TestEngine(), ReadingSelectionEngine {
            override fun selectReading(index: Int): EngineUpdate {
                readingCalls++
                return EngineUpdate(EngineSnapshot("ni'426", "ni hao"))
            }
        }
        val controller = InputSessionController(RecordingConnection(), { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.SelectReading(0))
        assertEquals(1, readingCalls)
        assertEquals("ni'426", controller.state.snapshot.rawInput)
        controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD },
            InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.SelectReading(0))
        controller.handle(InputCommand.LiteralText("4"))
        assertEquals(1, readingCalls)
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `personal candidate uses engine script and preserves its learning identity`() {
        val connection = RecordingConnection()
        val personalization = RecordingPersonalization(listOf(PersonalSuggestion("fixture", "中國", 3)))
        val engine = object : InputEngine by TestEngine(), CandidateTextNormalizer {
            override fun normalizeCandidateText(text: String) = "中国"
        }
        val controller = InputSessionController(connection, { engine }, personalization)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("zhongguo"))
        assertTrue(controller.state.snapshot.candidates.first().text == "中国")
        controller.handle(InputCommand.SelectCandidate(0))
        assertTrue(connection.commits.single() == "中国")
        assertEquals(listOf("fixture"), personalization.usedIds)
    }

    @Test
    fun `failed personal text conversion hides the suggestion and leaves base candidates usable`() {
        val personalization = RecordingPersonalization(listOf(PersonalSuggestion("fixture", "中國", 3)))
        val engine = object : InputEngine by TestEngine(), CandidateTextNormalizer {
            override fun normalizeCandidateText(text: String): String = error("Conversion unavailable")
        }
        val controller = InputSessionController(RecordingConnection(), { engine }, personalization)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("zhongguo"))
        controller.refreshPersonalization()
        assertTrue(controller.state.snapshot.candidates.none { it.id.startsWith("personal:") })
        assertTrue(controller.state.snapshot.candidates.isNotEmpty())
    }

    @Test
    fun `candidate paging and selection do not rewrite unchanged editor composition`() {
        val connection = ComposingRecordingConnection()
        val controller = controller(connection, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("ni"))
        val updates = connection.compositionUpdates
        controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
        assertEquals(updates, connection.compositionUpdates)
        assertEquals("ni", connection.composing)
        controller.handle(InputCommand.SelectCandidate(0))
        assertEquals(updates, connection.compositionUpdates)
        assertEquals("", connection.composing)
        assertEquals(listOf("你好"), connection.commits)
    }

    @Test
    fun `learns a Chinese commit under its original pinyin`() {
        val connection = RecordingConnection()
        val personalization = RecordingPersonalization()
        val controller = controller(connection, personalization)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        "nihao".forEach { controller.handle(InputCommand.Text(it.toString())) }
        controller.handle(InputCommand.Space)

        assertEquals(listOf("你好"), connection.commits)
        assertEquals("nihao", personalization.learned.single().shortcut)
        assertEquals("你好", personalization.learned.single().value)
        assertTrue(personalization.learned.single().allowed)
    }

    @Test
    fun `committing a candidate replaces the composing preedit`() {
        val connection = ComposingRecordingConnection()
        val controller = controller(connection, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        "nihao".forEach { controller.handle(InputCommand.Text(it.toString())) }
        controller.handle(InputCommand.Space)

        assertEquals(listOf("你好"), connection.commits)
        assertEquals("", connection.composing)
    }

    @Test
    fun `reset cancels a composing preedit without committing it`() {
        val connection = ComposingRecordingConnection()
        val controller = controller(connection, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("n"))
        controller.reset()

        assertTrue(connection.commits.isEmpty())
        assertEquals("", connection.composing)
    }

    @Test
    fun `password input bypasses engines and personalization`() {
        val connection = RecordingConnection()
        val personalization = RecordingPersonalization()
        val engine = TestEngine()
        val controller = InputSessionController(connection, { engine }, personalization)
        val passwordEditor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        controller.start(passwordEditor, InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("s"))

        assertEquals(listOf("s"), connection.commits)
        assertEquals(0, engine.handledKeys)
        assertTrue(personalization.learned.isEmpty())
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `password session does not create an engine`() {
        var created = 0
        val connection = RecordingConnection()
        val passwordEditor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val controller = InputSessionController(
            connection = connection,
            engineProvider = {
                created += 1
                TestEngine()
            },
            personalization = RecordingPersonalization(),
        )

        controller.start(passwordEditor, InputLanguage.CHINESE, PrivacyConfiguration())

        assertEquals(0, created)
    }

    @Test
    fun `closing a session clears candidates from the idle input view`() {
        val connection = ComposingRecordingConnection()
        val controller = controller(connection, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("n"))

        controller.close()

        assertFalse(controller.state.snapshot.isComposing)
        assertTrue(controller.state.snapshot.candidates.isEmpty())
        assertEquals("", connection.composing)
    }

    @Test
    fun `personal candidate records the exact entry and offsets engine highlight`() {
        val connection = RecordingConnection()
        val personalization = RecordingPersonalization(
            suggestions = listOf(PersonalSuggestion("term-id", "你号", 9)),
        )
        val controller = controller(connection, personalization)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("n"))
        controller.handle(InputCommand.Text("i"))

        assertEquals(listOf("你号", "你好"), controller.state.snapshot.candidates.map(Candidate::text))
        assertEquals(1, controller.state.snapshot.highlightedIndex)
        controller.handle(InputCommand.SelectCandidate(0))
        assertEquals(listOf("你号"), connection.commits)
        assertEquals(listOf("term-id"), personalization.usedIds)
    }

    @Test
    fun `refreshPersonalization overlays a background result without replaying input`() {
        val connection = ComposingRecordingConnection()
        val personalization = RecordingPersonalization()
        val controller = controller(connection, personalization)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("n"))
        controller.handle(InputCommand.Text("i"))
        assertEquals(listOf("你好"), controller.state.snapshot.candidates.map(Candidate::text))
        assertEquals("ni", connection.composing)

        personalization.suggestions = listOf(PersonalSuggestion("async-id", "你号", 9))
        controller.refreshPersonalization()

        assertEquals(listOf("你号", "你好"), controller.state.snapshot.candidates.map(Candidate::text))
        assertEquals("ni", connection.composing)
    }

    @Test
    fun `sessions that cannot learn do not read or write personal candidates`() {
        val connection = RecordingConnection()
        val personalization = RecordingPersonalization(
            suggestions = listOf(PersonalSuggestion("private", "私词", 20)),
        )
        val controller = controller(connection, personalization)
        val emailEditor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        controller.start(emailEditor, InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("n"))
        controller.handle(InputCommand.Space)

        assertEquals(0, personalization.suggestionCalls)
        assertTrue(controller.state.snapshot.candidates.none { it.text == "私词" })
        assertTrue(personalization.learned.isEmpty())
    }

    @Test
    fun `a repeated privacy configuration is a no-op`() {
        val connection = ComposingRecordingConnection()
        val controller = controller(connection, RecordingPersonalization())
        val configuration = PrivacyConfiguration(learningEnabled = true, incognitoMode = false)
        controller.start(textEditor(), InputLanguage.CHINESE, configuration)
        connection.compositionUpdates = 0

        // Same configuration: no re-evaluation, so the editor is untouched.
        assertFalse(controller.updatePrivacy(configuration))
        assertEquals(0, connection.compositionUpdates)

        // A genuinely different configuration still clears the pre-edit.
        assertTrue(controller.updatePrivacy(PrivacyConfiguration(learningEnabled = false)))
        assertTrue(connection.compositionUpdates > 0)
    }

    @Test
    fun `privacy changes invalidate an already open session`() {
        val connection = ComposingRecordingConnection()
        val personalization = RecordingPersonalization(
            suggestions = listOf(PersonalSuggestion("private", "私词", 20)),
        )
        val controller = controller(connection, personalization)
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("n"))
        personalization.suggestionCalls = 0

        assertTrue(
            controller.updatePrivacy(
                PrivacyConfiguration(learningEnabled = false),
            ),
        )

        assertFalse(controller.state.privacy.personalizationAllowed)
        assertTrue(connection.commits.isEmpty())
        assertEquals("", connection.composing)
        assertEquals(0, personalization.suggestionCalls)
    }

    @Test
    fun `deferred startup uses the immediate engine without touching the pack provider`() {
        val connection = RecordingConnection()
        val immediate = PreparedTestEngine("immediate")
        var providerCalls = 0
        val controller = InputSessionController(
            connection = connection,
            engineProvider = {
                providerCalls += 1
                immediate
            },
            personalization = RecordingPersonalization(),
            languagePackProvider = { error("language-pack provider must be deferred") },
            deferHeavyEngineCreation = true,
        )

        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration(), languagePackKey = "pack@1")

        assertEquals(1, providerCalls)
        assertEquals("pack@1", controller.state.languagePackKey)
        assertTrue(immediate.started)
    }

    @Test
    fun `prepared engine is adopted only for the matching editor context`() {
        val connection = RecordingConnection()
        val immediate = PreparedTestEngine("immediate")
        val controller = InputSessionController(
            connection = connection,
            engineProvider = { immediate },
            personalization = RecordingPersonalization(),
            deferHeavyEngineCreation = true,
        )
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        val preparedEngine = PreparedTestEngine("prepared")
        val prepared = PreparedInputEngine(
            language = InputLanguage.CHINESE,
            languagePackKey = null,
            packageName = null,
            privacy = controller.state.privacy,
            snapshot = EngineSnapshot.Empty,
            engine = preparedEngine,
        )

        assertTrue(controller.adoptPreparedEngine(prepared))
        assertTrue(immediate.closed)
        assertFalse(preparedEngine.closed)

        val stale = PreparedTestEngine("stale")
        val staleResult = PreparedInputEngine(
            language = InputLanguage.CHINESE,
            languagePackKey = null,
            packageName = "other.editor",
            privacy = controller.state.privacy,
            snapshot = EngineSnapshot.Empty,
            engine = stale,
        )
        assertFalse(controller.adoptPreparedEngine(staleResult))
        assertTrue(stale.closed)

        val oldPrivacyEngine = PreparedTestEngine("old-privacy")
        val oldPrivacy = controller.state.privacy
        val oldPrivacyResult = PreparedInputEngine(
            language = InputLanguage.CHINESE,
            languagePackKey = null,
            packageName = null,
            privacy = oldPrivacy,
            snapshot = EngineSnapshot.Empty,
            engine = oldPrivacyEngine,
        )
        assertTrue(controller.updatePrivacy(PrivacyConfiguration(learningEnabled = false)))
        assertFalse(controller.adoptPreparedEngine(oldPrivacyResult))
        assertTrue(oldPrivacyEngine.closed)
    }

    @Test
    fun `prepared engine is closed when a composition is active`() {
        val controller = InputSessionController(
            connection = ComposingRecordingConnection(),
            engineProvider = { PreparedTestEngine("immediate") },
            personalization = RecordingPersonalization(),
            deferHeavyEngineCreation = true,
        )
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("n"))
        val preparedEngine = PreparedTestEngine("prepared")
        val prepared = PreparedInputEngine(
            language = InputLanguage.CHINESE,
            languagePackKey = null,
            packageName = null,
            privacy = controller.state.privacy,
            snapshot = EngineSnapshot.Empty,
            engine = preparedEngine,
        )

        assertFalse(controller.adoptPreparedEngine(prepared))
        assertTrue(preparedEngine.closed)
    }

    @Test
    fun `unconsumed character and space fall back to the editor`() {
        val connection = RecordingConnection()
        val engine = UnconsumingEngine()
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("¥"))
        controller.handle(InputCommand.Space)

        assertEquals(listOf("¥", " "), connection.commits)
    }

    @Test
    fun `no enter action flag sends newline without invoking send even in a private editor`() {
        for (private in listOf(false, true)) {
            val connection = RecordingConnection()
            val controller = InputSessionController(connection, { TestEngine() }, RecordingPersonalization())
            val info = textEditor().apply {
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                if (private) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            controller.start(info, InputLanguage.CHINESE, PrivacyConfiguration())
            controller.handle(InputCommand.Enter)
            assertEquals(1, connection.enterKeys)
            assertTrue(connection.editorActions.isEmpty())
            controller.close()
        }
    }

    @Test
    fun `empty composition uses the editor search action and falls back only if declined`() {
        val connection = RecordingConnection()
        val controller = InputSessionController(connection, { TestEngine() }, RecordingPersonalization())
        controller.start(textEditor().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }, InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Enter)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), connection.editorActions)
        assertEquals(1, connection.enterKeys)
        controller.close()
    }

    @Test
    fun `enter is sent to the engine before falling back for unknown composition`() {
        val connection = RecordingConnection()
        val engine = UnconsumingEngine(composition = "qz")
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("q"))
        controller.handle(InputCommand.Text("z"))

        controller.handle(InputCommand.Enter)

        assertEquals(1, engine.enterCalls)
        assertEquals(listOf("qz"), connection.commits)
        assertEquals(1, connection.enterKeys)
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `a character reflected in the update snapshot is not duplicated`() {
        val connection = ComposingRecordingConnection()
        val engine = StaleSnapshotEngine()
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("q"))

        assertTrue(connection.commits.isEmpty())
        assertEquals("q", connection.composing)
        assertEquals("q", controller.state.snapshot.rawInput)
    }

    @Test
    fun `unconsumed character preserves the composition from before a cleared update`() {
        val connection = ComposingRecordingConnection()
        val engine = ClearingUnconsumedEngine()
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("n"))
        controller.handle(InputCommand.Text("¥"))

        assertEquals(listOf("n", "¥"), connection.commits)
        assertEquals("", connection.composing)
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `candidate selection uses the snapshot returned by the key event`() {
        val connection = ComposingRecordingConnection()
        val engine = StaleCandidateEngine()
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("q"))
        controller.handle(InputCommand.SelectCandidate(0))

        assertEquals(listOf("候选"), connection.commits)
    }

    @Test
    fun `enter ignores a stale engine property after a completed commit`() {
        val connection = RecordingConnection()
        val engine = StaleAfterCommitEngine()
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("n"))
        controller.handle(InputCommand.Space)
        controller.handle(InputCommand.Enter)

        assertEquals(listOf("你"), connection.commits)
        assertEquals(1, connection.enterKeys)
        assertEquals(0, engine.enterCalls)
    }

    @Test
    fun `unconsumed backspace flushes a composing snapshot before editor deletion`() {
        val connection = ComposingRecordingConnection()
        val engine = UnconsumingEngine(composition = "qz")
        val controller = InputSessionController(connection, { engine }, RecordingPersonalization())
        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())

        controller.handle(InputCommand.Text("q"))
        controller.handle(InputCommand.Text("z"))
        controller.handle(InputCommand.Backspace)

        assertEquals(listOf("qz"), connection.commits)
        assertEquals("", connection.composing)
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `engine start failure degrades to direct editor input`() {
        val connection = RecordingConnection()
        val controller = InputSessionController(
            connection = connection,
            engineProvider = { error("native engine unavailable") },
            personalization = RecordingPersonalization(),
        )

        controller.start(textEditor(), InputLanguage.CHINESE, PrivacyConfiguration())
        controller.handle(InputCommand.Text("¥"))
        controller.handle(InputCommand.Space)
        controller.handle(InputCommand.Backspace)

        assertEquals(listOf("¥", " "), connection.commits)
        assertFalse(controller.state.snapshot.isComposing)
    }

    @Test
    fun `selected language pack is retried after asynchronous discovery`() {
        val connection = RecordingConnection()
        var discovered = false
        val packEngine = TestEngine()
        val controller = InputSessionController(
            connection = connection,
            engineProvider = { TestEngine() },
            personalization = RecordingPersonalization(),
            languagePackProvider = { if (discovered) packEngine else null },
            languagePackDiscoveryComplete = { discovered },
        )

        controller.start(
            textEditor(),
            InputLanguage.CHINESE,
            PrivacyConfiguration(),
            languagePackKey = "pack@1",
        )
        assertEquals("pack@1", controller.state.languagePackKey)

        discovered = true
        assertTrue(controller.reloadLanguagePackIfIdle())
        assertEquals("pack@1", controller.state.languagePackKey)
    }

    private fun controller(
        connection: EditorConnection,
        personalization: RecordingPersonalization,
    ) = InputSessionController(connection, { TestEngine() }, personalization)

    private fun textEditor() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    private data class LearningCall(
        val shortcut: String,
        val value: String,
        val allowed: Boolean,
    )

    private class RecordingPersonalization(
        var suggestions: List<PersonalSuggestion> = emptyList(),
    ) : PersonalizationStore {
        val learned = mutableListOf<LearningCall>()
        val usedIds = mutableListOf<String>()
        var suggestionCalls = 0

        override fun suggestionsFor(
            prefix: String,
            language: InputLanguage,
            limit: Int,
        ): List<PersonalSuggestion> {
            suggestionCalls += 1
            return if (prefix.isEmpty()) emptyList() else suggestions.take(limit)
        }

        override fun learn(
            shortcut: String,
            value: String,
            language: InputLanguage,
            learningAllowed: Boolean,
        ) {
            learned += LearningCall(shortcut, value, learningAllowed)
        }

        override fun recordUse(id: String, learningAllowed: Boolean) {
            if (learningAllowed) usedIds += id
        }
    }

    private class RecordingConnection : EditorConnection {
        val commits = mutableListOf<String>()
        val editorActions = mutableListOf<Int>()
        var enterKeys = 0

        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() = Unit
        override fun commitText(text: String) {
            commits += text
        }
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int): Boolean { editorActions += actionId; return false }
        override fun sendEnterKey() {
            enterKeys += 1
        }
    }

    private class PreparedTestEngine(
        private val id: String,
    ) : InputEngine {
        private var current = EngineSnapshot.Empty
        var started = false
        var closed = false

        override val descriptor = EngineDescriptor(
            id = id,
            displayName = id,
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )

        override val snapshot: EngineSnapshot
            get() = current

        override fun start(context: EditorContext): EngineSnapshot {
            started = true
            current = EngineSnapshot.Empty
            return current
        }

        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> {
                current = EngineSnapshot(key.text, key.text)
                EngineUpdate(current)
            }
            else -> EngineUpdate(current, consumed = false)
        }

        override fun selectCandidate(index: Int) = EngineUpdate(current, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            closed = true
            current = EngineSnapshot.Empty
        }
    }

    /** Models Android's composing span so candidate replacement regressions are visible. */
    private class ComposingRecordingConnection : EditorConnection {
        val commits = mutableListOf<String>()
        var composing = ""
        var enterKeys = 0
        var compositionUpdates = 0

        override fun setComposingText(text: String) {
            compositionUpdates++
            composing = text
        }

        override fun finishComposingText() {
            if (composing.isNotEmpty()) commits += composing
            composing = ""
        }

        override fun commitText(text: String) {
            composing = ""
            if (text.isNotEmpty()) commits += text
        }

        override fun deleteBeforeCursor() = Unit

        override fun performEditorAction(actionId: Int): Boolean = false

        override fun sendEnterKey() {
            enterKeys += 1
        }
    }

    private class TestEngine : InputEngine {
        private var rawInput = ""
        private var current = EngineSnapshot.Empty
        var handledKeys = 0
            private set
        var enterCalls = 0

        override val descriptor = EngineDescriptor(
            id = "test",
            displayName = "Test",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )
        override val snapshot: EngineSnapshot
            get() = current

        override fun start(context: EditorContext): EngineSnapshot = reset()

        override fun handle(key: EngineKey): EngineUpdate {
            handledKeys += 1
            if (key == EngineKey.Enter) enterCalls += 1
            return when (key) {
                is EngineKey.Character -> compose(rawInput + key.text)
                EngineKey.Backspace -> compose(rawInput.dropLast(1))
                EngineKey.Space,
                EngineKey.Enter,
                -> commitBest()
            }
        }

        override fun selectCandidate(index: Int): EngineUpdate =
            if (index == 0 && current.candidates.isNotEmpty()) commitBest() else EngineUpdate(current, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            rawInput = ""
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() = Unit

        private fun compose(value: String): EngineUpdate {
            rawInput = value
            current = if (value.isEmpty()) {
                EngineSnapshot.Empty
            } else {
                EngineSnapshot(value, value, listOf(Candidate("engine:0", "你好")))
            }
            return EngineUpdate(current)
        }

        private fun commitBest(): EngineUpdate {
            val committed = if (rawInput.isEmpty()) "" else "你好"
            reset()
            return EngineUpdate(current, committedText = committed, consumed = committed.isNotEmpty())
        }
    }

    private class UnconsumingEngine(
        private val composition: String = "",
    ) : InputEngine {
        private var current = EngineSnapshot.Empty
        private var raw = ""
        var enterCalls = 0

        override val descriptor = EngineDescriptor(
            id = "unconsuming",
            displayName = "Unconsuming",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )
        override val snapshot: EngineSnapshot
            get() = current

        override fun start(context: EditorContext): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun handle(key: EngineKey): EngineUpdate {
            if (key == EngineKey.Enter) enterCalls += 1
            return when (key) {
                is EngineKey.Character -> if (composition.isNotEmpty()) {
                    raw += key.text
                    current = EngineSnapshot(raw, raw)
                    EngineUpdate(current)
                } else {
                    EngineUpdate(current, consumed = false)
                }
                EngineKey.Space -> EngineUpdate(current, consumed = false)
                EngineKey.Enter -> EngineUpdate(current, consumed = false)
                EngineKey.Backspace -> EngineUpdate(current, consumed = false)
            }
        }

        override fun selectCandidate(index: Int) = EngineUpdate(current, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            raw = ""
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            reset()
        }
    }

    private class StaleSnapshotEngine : InputEngine {
        private var current = EngineSnapshot.Empty

        override val descriptor = EngineDescriptor(
            id = "stale",
            displayName = "Stale",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )

        override val snapshot: EngineSnapshot
            get() = EngineSnapshot.Empty

        override fun start(context: EditorContext): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun handle(key: EngineKey): EngineUpdate =
            if (key is EngineKey.Character) {
                current = EngineSnapshot(key.text, key.text)
                EngineUpdate(current, consumed = false)
            } else {
                EngineUpdate(current, consumed = false)
            }

        override fun selectCandidate(index: Int) = EngineUpdate(current, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            reset()
        }
    }

    private class ClearingUnconsumedEngine : InputEngine {
        private var current = EngineSnapshot.Empty

        override val descriptor = EngineDescriptor(
            id = "clearing-unconsumed",
            displayName = "Clearing unconsumed",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )

        override val snapshot: EngineSnapshot
            get() = current

        override fun start(context: EditorContext): EngineSnapshot = reset()

        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> if (key.text == "¥") {
                current = EngineSnapshot.Empty
                EngineUpdate(current, consumed = false)
            } else {
                current = EngineSnapshot(key.text, key.text)
                EngineUpdate(current)
            }
            else -> EngineUpdate(current, consumed = false)
        }

        override fun selectCandidate(index: Int) = EngineUpdate(current, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            reset()
        }
    }

    private class StaleAfterCommitEngine : InputEngine {
        private var stale = EngineSnapshot.Empty
        var enterCalls = 0

        override val descriptor = EngineDescriptor(
            id = "stale-after-commit",
            displayName = "Stale after commit",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )

        override val snapshot: EngineSnapshot
            get() = stale

        override fun start(context: EditorContext): EngineSnapshot {
            stale = EngineSnapshot.Empty
            return stale
        }

        override fun handle(key: EngineKey): EngineUpdate = when (key) {
            is EngineKey.Character -> {
                stale = EngineSnapshot(key.text, key.text)
                EngineUpdate(stale)
            }
            EngineKey.Space -> EngineUpdate(EngineSnapshot.Empty, committedText = "你")
            EngineKey.Enter -> {
                enterCalls += 1
                EngineUpdate(stale, consumed = false)
            }
            EngineKey.Backspace -> EngineUpdate(stale, consumed = false)
        }

        override fun selectCandidate(index: Int) = EngineUpdate(stale, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(stale, consumed = false)

        override fun reset(): EngineSnapshot {
            stale = EngineSnapshot.Empty
            return stale
        }

        override fun close() = Unit
    }

    /** Exposes an empty property snapshot while returning a fresh update. */
    private class StaleCandidateEngine : InputEngine {
        private var current = EngineSnapshot.Empty

        override val descriptor = EngineDescriptor(
            id = "stale-candidate",
            displayName = "Stale candidate",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )

        override val snapshot: EngineSnapshot
            get() = EngineSnapshot.Empty

        override fun start(context: EditorContext): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun handle(key: EngineKey): EngineUpdate {
            current = EngineSnapshot(
                rawInput = "q",
                composition = "q",
                candidates = listOf(Candidate("fresh:0", "候选")),
            )
            return EngineUpdate(current)
        }

        override fun selectCandidate(index: Int): EngineUpdate {
            current = EngineSnapshot.Empty
            return EngineUpdate(current, committedText = "候选")
        }

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            current = EngineSnapshot.Empty
        }
    }
}
