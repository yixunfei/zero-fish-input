package dev.zeroinput.ime.core

import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CandidateKind
import dev.zeroinput.engine.api.CandidateTextNormalizer
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.NextWordPredictor
import dev.zeroinput.engine.api.PersonalFrequencyStore
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.engine.api.ReadingSelectionEngine
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.CompositionEditingEngine
import dev.zeroinput.ime.core.privacy.EditorPrivacyPolicy
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.core.privacy.SessionPrivacy

class InputSessionController(
    private val connection: EditorConnection,
    private val engineProvider: (InputLanguage) -> InputEngine,
    private val personalization: PersonalizationStore,
    private val privacyPolicy: EditorPrivacyPolicy = EditorPrivacyPolicy(),
    private val onStateChanged: (InputSessionState) -> Unit = {},
    private val languagePackProvider: (String) -> InputEngine? = { null },
    private val languagePackDiscoveryComplete: () -> Boolean = { true },
    /**
     * When true, [engineProvider] is expected to return a lightweight
     * fallback.  Native and language-pack engines are installed later through
     * [adoptPreparedEngine], so session creation never performs heavy work on
     * the IME input thread.
     */
    private val deferHeavyEngineCreation: Boolean = false,
    nextWordPredictor: NextWordPredictor = NextWordPredictor.Empty,
    private val wordAssociationsEnabled: () -> Boolean = { true },
    private val kaomojiCandidates: KaomojiCandidateSource = KaomojiCandidateSource.Empty,
) : AutoCloseable {
    private var engine: InputEngine? = null
    private var language = InputLanguage.CHINESE
    private var languagePackKey: String? = null
    private var editorInfo = EditorInfo()
    private var privacy = SessionPrivacy(
        isSensitive = false,
        suggestionsAllowed = true,
        learningAllowed = false,
        reason = dev.zeroinput.ime.core.privacy.PrivacyReason.USER_DISABLED,
    )
    // The configuration that produced [privacy].  evaluate() is deterministic
    // in editorInfo and configuration, and editorInfo only changes through
    // start(), which resets this marker; so an equal configuration cannot
    // produce a different session policy.
    private var evaluatedFor: PrivacyConfiguration? = null
    private var routes: List<CandidateRoute?> = emptyList()
    /**
     * Engine-owned state without the personal-candidate overlay.  Keeping
     * this snapshot separately lets a background personalization query
     * refresh the visible candidates without replaying a key event or asking
     * the engine to mutate its composition.
     */
    private var rawEngineSnapshot = EngineSnapshot.Empty
    private val recentComposition = RecentComposition()
    private val candidateWindow = CandidateWindow()
    private val personalPaging = PersonalCandidatePaging()
    private val associations = WordAssociationSession(nextWordPredictor) { language, words ->
        // The privacy policy is re-evaluated at query time so a tightening
        // takes effect on the very next commit, matching the strip's own gate.
        if (privacy.personalizationAllowed) {
            (personalization as? PersonalFrequencyStore)?.frequenciesFor(words, language).orEmpty()
        } else {
            emptyMap()
        }
    }

    var state = InputSessionState()
        private set

    /**
     * Degradation counter for personalization writes.  A failing store must
     * never break the input path, so learning exceptions are deliberately
     * swallowed; this count keeps them observable for diagnostics and tests
     * without logging any user content.
     */
    var learningFailureCount = 0
        private set

    fun start(
        editorInfo: EditorInfo,
        initialLanguage: InputLanguage,
        privacyConfiguration: PrivacyConfiguration,
        languagePackKey: String? = null,
    ) {
        // A controller is normally created for one editor session, but making
        // start idempotent keeps a reused instance from leaking a native
        // engine or leaving a composing span owned by the previous editor.
        val hadEngine = engine != null
        closeEngine()
        if (hadEngine) connection.clearComposingText()
        this.editorInfo = editorInfo
        privacy = privacyPolicy.evaluate(editorInfo, privacyConfiguration)
        evaluatedFor = privacyConfiguration
        language = initialLanguage
        this.languagePackKey = languagePackKey
        replaceEngine()
    }

    /**
     * Re-evaluates the active editor when a global privacy setting changes.
     *
     * A session must not continue using an older, more permissive policy after
     * the user enables incognito mode or disables learning.  Clearing the
     * pre-edit is deliberate: committing it could disclose text that the new
     * policy is meant to protect.
     *
     * @return true when the engine/session state was recreated.
     */
    fun updatePrivacy(configuration: PrivacyConfiguration): Boolean {
        // A repeated configuration cannot change the outcome: evaluate() is
        // deterministic and editorInfo only changes through start(), which
        // clears this marker.  Skipping the allocation keeps the tightening
        // semantics intact because a genuine change always compares unequal.
        if (configuration == evaluatedFor) return false
        val updated = privacyPolicy.evaluate(editorInfo, configuration)
        evaluatedFor = configuration
        if (updated == privacy) return false
        privacy = updated
        runCatching { engine?.reset() }
            .onFailure { closeEngine() }
        connection.clearComposingText()
        replaceEngine()
        return true
    }

    fun handle(command: InputCommand) {
        if (command is InputCommand.SelectCandidate && command.candidateId != null &&
            state.snapshot.candidates.getOrNull(command.visibleIndex)?.id != command.candidateId) return
        if (!associationsAllowed()) invalidateWordAssociations()
        when (command) {
            is InputCommand.Text -> associations.hide()
            is InputCommand.SelectCandidate, is InputCommand.ChangeCandidatePage -> Unit
            InputCommand.Space -> if (!state.snapshot.isComposing) associations.clear()
            else -> associations.clear()
        }
        if (command != InputCommand.ReconvertLast) invalidateReconversion(publishState = false)
        if (command !is InputCommand.ChangeCandidatePage && command !is InputCommand.SelectCandidate &&
            command != InputCommand.Space) candidateWindow.clear()
        if (privacy.isSensitive) {
            handleSensitive(command)
            return
        }
        val activeEngine = engine
        if (activeEngine == null) {
            // An optional/native engine can become unavailable while a
            // session is open. Never drop user input just because the
            // suggestion layer is unavailable; route basic editing directly
            // to the editor until the next session can recreate the engine.
            handleEditorFallback(command)
            return
        }
        try {
            when (command) {
                is InputCommand.Text -> handleKey(activeEngine, EngineKey.Character(command.value))
                is InputCommand.LiteralText -> commitLiteral(activeEngine, command.value)
                is InputCommand.PairedText -> commitPaired(activeEngine, command.opening, command.closing)
                InputCommand.Backspace -> handleKey(activeEngine, EngineKey.Backspace, fallbackBackspace = true)
                InputCommand.Space -> {
                    if (state.snapshot.isComposing && state.snapshot.candidates.isNotEmpty() &&
                        (candidateWindow.isBrowsing || personalPaging.isBrowsing || state.modelRanked)) {
                        selectCandidate(activeEngine, state.snapshot.highlightedIndex)
                    } else handleKey(activeEngine, EngineKey.Space)
                }
                InputCommand.Enter -> handleEnter(activeEngine)
                InputCommand.ReconvertLast -> reconvert(activeEngine)
                InputCommand.UndoSelection -> (activeEngine as? CompositionEditingEngine)?.let {
                    apply(it.undoSelection())
                }
                InputCommand.SelectSyllable -> (activeEngine as? CompositionEditingEngine)?.let {
                    apply(it.selectSyllable())
                }
                is InputCommand.SelectCandidate -> {
                    if (command.candidateId == null ||
                        state.snapshot.candidates.getOrNull(command.visibleIndex)?.id == command.candidateId) {
                        selectCandidate(activeEngine, command.visibleIndex)
                    }
                }
                is InputCommand.ChangeCandidatePage -> {
                    if (!rawEngineSnapshot.isComposing) return
                    if (personalPaging.changePage(command.direction, candidateWindow.snapshot(rawEngineSnapshot))) {
                        publish(rawEngineSnapshot)
                    } else apply(candidateWindow.changePage(activeEngine, rawEngineSnapshot, command.direction))
                }
                is InputCommand.SelectReading -> (activeEngine as? ReadingSelectionEngine)?.let {
                    apply(it.selectReading(command.index))
                }
            }
        } catch (_: Throwable) {
            // Native engines are optional and can fail after a session has
            // already started (for example when a JNI call loses its native
            // context).  Basic editing must remain usable in that case.
            recoverFromEngineFailure(activeEngine)
            handleEditorFallback(command)
        }
    }

    fun switchLanguage() {
        setLanguage(if (language == InputLanguage.CHINESE) InputLanguage.ENGLISH else InputLanguage.CHINESE)
    }

    fun setLanguage(target: InputLanguage, packKey: String? = null) {
        if (language == target && languagePackKey == packKey) return
        language = target
        languagePackKey = packKey
        connection.clearComposingText()
        replaceEngine()
    }

    fun reset() {
        associations.clear()
        candidateWindow.clear()
        invalidateReconversion(publishState = false)
        runCatching { engine?.reset() }
            .onFailure { closeEngine() }
        connection.clearComposingText()
        publish(EngineSnapshot.Empty)
    }

    /** An external cursor move hands the visible preedit back to the editor, without learning. */
    fun finishCompositionForCursorMove() {
        if (!state.snapshot.isComposing) return
        associations.clear()
        candidateWindow.clear()
        invalidateReconversion(publishState = false)
        runCatching { engine?.reset() }.onFailure { closeEngine() }
        connection.finishComposingText()
        publish(EngineSnapshot.Empty)
    }

    /**
     * Re-publishes the current engine state after an asynchronous personal
     * suggestion query completes.  This method never changes the composing
     * text or invokes the engine; callers should invoke it on the same thread
     * that owns this controller (the IME main thread in the Android service).
     */
    fun refreshPersonalization() {
        publish(rawEngineSnapshot)
    }

    /** Re-runs the current association context after its predictor is ready. */
    fun refreshWordAssociations() {
        if (!associationsAllowed()) {
            invalidateWordAssociations()
            return
        }
        associations.refresh { value ->
            (engine as? CandidateTextNormalizer)?.normalizeCandidateText(value) ?: value
        }
        publish(rawEngineSnapshot)
    }

    /** Cursor, panel, settings and external-commit boundaries discard all prediction context. */
    fun invalidateWordAssociations() {
        val visible = associations.candidates.isNotEmpty()
        associations.clear()
        if (visible) publish(rawEngineSnapshot)
    }

    private fun associationsAllowed(): Boolean = wordAssociationsEnabled() && privacy.personalizationAllowed &&
        !privacy.isSensitive && EditorInputOptions.from(editorInfo).layout == EditorLayout.TEXT

    private fun commitPredictableText(text: String): Boolean {
        val committed = connection.commitText(text)
        if (committed && associationsAllowed()) associations.committed(text, language) { value ->
            (engine as? CandidateTextNormalizer)?.normalizeCandidateText(value) ?: value
        }
        else associations.clear()
        return committed
    }

    /** Main-thread-only handoff. A revision is consumed once and routes remain engine-owned. */
    fun applyModelWinner(revision: Long, candidateIndex: Int): Boolean {
        if (revision != state.candidateRevision || candidateWindow.isBrowsing || personalPaging.isBrowsing) return false
        val candidate = modelCandidates().getOrNull(candidateIndex) ?: return false
        val promoted = ModelRankingPolicy.promote(state.snapshot, candidate.id) ?: return false
        rebuildRoutes(promoted)
        state = state.copy(snapshot = promoted, modelRanked = true, candidateRevision = state.candidateRevision + 1)
        onStateChanged(state)
        return true
    }

    fun modelCandidates(): List<Candidate> = if (candidateWindow.isBrowsing || personalPaging.isBrowsing) emptyList()
        else ModelRankingPolicy.eligible(state)

    fun clearModelRanking() {
        if (state.modelRanked) publish(rawEngineSnapshot)
    }

    fun invalidateReconversion(publishState: Boolean = true) {
        val changed = recentComposition.available
        recentComposition.clear()
        (connection as? ReconversionEditorConnection)?.invalidateReconversion()
        if (changed && publishState) publish(rawEngineSnapshot)
    }

    private fun reconvert(activeEngine: InputEngine) {
        val editing = activeEngine as? CompositionEditingEngine ?: return
        val editor = connection as? ReconversionEditorConnection ?: return
        if (state.snapshot.isComposing || !privacy.suggestionsAllowed) return
        recentComposition.consume { reading, text ->
            val restored = editing.restoreComposition(reading)
            if (restored.consumed && restored.snapshot.isComposing && editor.reopenCommittedText(text)) {
                apply(restored)
            } else {
                activeEngine.reset()
                recentComposition.clear()
                publish(EngineSnapshot.Empty)
            }
        }
    }

    override fun close() {
        closeEngine()
        routes = emptyList()
        connection.clearComposingText()
        // The input view can outlive the editor connection. Publish an empty
        // snapshot so candidates from the previous application are never left
        // visible while the IME is idle or between sessions.
        publish(EngineSnapshot.Empty)
    }

    /**
     * Re-attempts selecting the configured language-pack engine.  The app
     * graph discovers packs asynchronously, so a session may initially have
     * fallen back to the built-in engine before discovery completed.  A
     * reload is only safe while there is no composing text; callers can retry
     * after the current composition is committed.
     */
    fun reloadEngineIfIdle(): Boolean {
        if (state.snapshot.isComposing) return false
        replaceEngine()
        return true
    }

    /** Backwards-compatible name for callers interested specifically in packs. */
    fun reloadLanguagePackIfIdle(): Boolean = reloadEngineIfIdle()

    /**
     * Installs an engine that was prepared for this exact editor context.
     * Sensitive/session changes reject the result and close it, preventing
     * stale native state from crossing an editor boundary. A small built-in
     * pinyin pre-edit may be restored into a prepared composition engine so
     * native startup never turns its first letter into literal editor text.
     * The caller retains no ownership after this method returns.
     */
    fun adoptPreparedEngine(prepared: PreparedInputEngine): Boolean {
        if (!matches(prepared)) {
            prepared.close()
            return false
        }
        val previous = state.snapshot
        if (previous.isComposing) return adoptPreparedComposition(prepared, previous)
        val candidate = prepared.takeEngine()
        if (candidate == null) return false
        closeEngine()
        routes = emptyList()
        engine = candidate
        languagePackKey = prepared.languagePackKey
        publish(prepared.snapshot)
        return true
    }

    /**
     * Transfers only a plain fallback pinyin pre-edit. Selected segments and
     * package engines can carry engine-specific state, so retaining the
     * existing engine is safer than attempting a lossy migration.
     */
    private fun adoptPreparedComposition(
        prepared: PreparedInputEngine,
        previous: EngineSnapshot,
    ): Boolean {
        val current = engine
        val input = previous.rawInput
        if (!canRestorePreparedComposition(current, input, previous)) {
            prepared.close()
            return false
        }
        val candidate = prepared.takeEngine() ?: return false
        val restored = runCatching {
            (candidate as? CompositionEditingEngine)?.restoreComposition(input)
        }.getOrNull()
        if (!isRestoredComposition(restored, input)) {
            closeSafely(candidate)
            return false
        }

        closeEngine()
        routes = emptyList()
        engine = candidate
        languagePackKey = prepared.languagePackKey
        apply(requireNotNull(restored))
        return true
    }

    private fun canRestorePreparedComposition(
        current: InputEngine?,
        input: String,
        snapshot: EngineSnapshot,
    ): Boolean = language == InputLanguage.CHINESE &&
        languagePackKey == null &&
        current?.descriptor?.isFallback == true &&
        current is CompositionEditingEngine &&
        !snapshot.canUndoSelection &&
        input.length in 1..MAX_HANDOFF_PINYIN_LENGTH &&
        input.all(::isHandoffPinyinCharacter)

    private fun isRestoredComposition(update: EngineUpdate?, input: String): Boolean =
        update != null && update.consumed && update.committedText.isEmpty() &&
            update.snapshot.isComposing && update.snapshot.rawInput == input

    private fun replaceEngine() {
        closeEngine()
        routes = emptyList()
        if (!privacy.suggestionsAllowed) {
            // Sensitive editors do not need an engine at all. Avoid creating
            // native sessions or loading dictionaries for a field that has
            // explicitly opted out of suggestions.
            publish(EngineSnapshot.Empty)
            return
        }

        // The service uses a small in-memory fallback here.  The normal
        // provider path remains available for pure-core callers and tests;
        // Android integration installs expensive engines through the prepared
        // engine handoff above.
        if (deferHeavyEngineCreation) {
            installImmediateEngine()
            return
        }

        val requestedPackKey = languagePackKey
        val packEngine = requestedPackKey?.let { key ->
            runCatching { languagePackProvider(key) }.getOrNull()
        }?.let { candidate ->
            val supportsLanguage = runCatching {
                language in candidate.descriptor.languages
            }.getOrDefault(false)
            if (supportsLanguage) {
                candidate
            } else {
                closeSafely(candidate)
                null
            }
        }
        // A null pack engine can mean that asynchronous discovery has not
        // completed yet.  Keep the requested key so the app can retry it when
        // the registry publishes a refresh.  Once discovery is known to be
        // complete, an absent key is stale and is cleared so the session state
        // cannot advertise a package that is no longer installed.
        val discoveryComplete = runCatching { languagePackDiscoveryComplete() }.getOrDefault(true)
        if (requestedPackKey != null && packEngine == null && discoveryComplete) {
            languagePackKey = null
        }
        if (packEngine != null) {
            val snapshot = startEngine(packEngine)
            if (snapshot != null) {
                engine = packEngine
                publish(snapshot)
                return
            }
            closeSafely(packEngine)
            languagePackKey = null
        }

        val baseEngine = runCatching { engineProvider(language) }.getOrNull()
        val snapshot = baseEngine?.let(::startEngine)
        if (snapshot != null) {
            engine = baseEngine
            publish(snapshot)
        } else {
            baseEngine?.let(::closeSafely)
            // No engine is a supported state.  Input commands will use the
            // direct editor path until a new session can create one.
            publish(EngineSnapshot.Empty)
        }
    }

    private fun installImmediateEngine() {
        val immediate = runCatching { engineProvider(language) }.getOrNull()
        val snapshot = immediate?.let(::startEngine)
        if (snapshot != null) {
            engine = immediate
            publish(snapshot)
        } else {
            immediate?.let(::closeSafely)
            publish(EngineSnapshot.Empty)
        }
    }

    private fun matches(prepared: PreparedInputEngine): Boolean =
        privacy.suggestionsAllowed &&
            prepared.language == language &&
            prepared.languagePackKey == languagePackKey &&
            prepared.packageName == editorInfo.packageName &&
            prepared.privacy == privacy &&
            prepared.snapshot.isComposing.not() &&
            runCatching { language in prepared.descriptor.languages }.getOrDefault(false)

    private companion object {
        const val MAX_HANDOFF_PINYIN_LENGTH = 128

        fun isHandoffPinyinCharacter(value: Char): Boolean =
            value in 'a'..'z' || value in '2'..'9' || value == '\'' || value == ';'
    }

    private fun handleKey(activeEngine: InputEngine, key: EngineKey, fallbackBackspace: Boolean = false) {
        if (state.modelRanked && key is EngineKey.Character &&
            key.text.singleOrNull()?.let { !it.isLetterOrDigit() && it != '\'' } == true) {
            selectCandidate(activeEngine, state.snapshot.highlightedIndex)
        }
        // The published state is authoritative for the key's starting
        // point. Adapter properties may lag behind the EngineUpdate that was
        // already rendered to the user.
        val before = state.snapshot
        val learningShortcut = before.rawInput.ifBlank { before.composition }
        val update = activeEngine.handle(key)
        apply(update, learningShortcut)
        if (update.consumed || update.committedText.isNotEmpty()) return

        if (fallbackBackspace) {
            if (reflectsBackspace(before, update.snapshot)) return
            // Do not leave an unconsumed pre-edit span attached to the
            // editor.  Commit the raw composition first, then route the
            // backspace to the editor just like any other unconsumed key.
            flushUnconsumedComposition(activeEngine, update.snapshot, before)
            connection.deleteBeforeCursor()
            return
        }

        val fallbackText = when (key) {
            is EngineKey.Character -> key.text
            EngineKey.Space -> " "
            else -> null
        } ?: return
        if (key is EngineKey.Character && reflectsCharacter(before, update.snapshot, key.text)) return
        commitUnconsumedKey(activeEngine, update.snapshot, before, fallbackText)
    }

    private fun apply(update: EngineUpdate, learningShortcut: String = "") {
        if (update.committedText.isNotEmpty()) {
            val reading = update.committedInput.ifBlank { learningShortcut }
            // commitText replaces the active composing span.  Finishing first
            // would make the pre-edit (for example, "ni") permanent and then
            // append the selected candidate (for example, "你").
            val committed = commitPredictableText(update.committedText)
            if (committed && language == InputLanguage.CHINESE && engine is CompositionEditingEngine &&
                connection is ReconversionEditorConnection && !update.snapshot.isComposing) {
                recentComposition.remember(reading, update.committedText)
            }
            if (committed && privacy.personalizationAllowed && update.learnable) {
                runCatching {
                    val learnedValue = update.committedText.trimEnd(' ')
                    if (learnedValue.isEmpty()) return@runCatching
                    personalization.learn(
                        shortcut = reading.ifBlank { learnedValue },
                        value = learnedValue,
                        language = language,
                        learningAllowed = privacy.learningAllowed,
                    )
                }.onFailure { learningFailureCount++ }
            }
        }
        if (update.snapshot.composition.isNotEmpty()) {
            if (update.committedText.isNotEmpty() || update.snapshot.composition != state.snapshot.composition) {
                connection.setComposingText(update.snapshot.composition)
            }
        } else if (update.committedText.isEmpty() && state.snapshot.isComposing) {
            // A commit already replaces Android's composing span. Paging and idle
            // keys need no extra binder calls to rewrite or clear that span.
            connection.clearComposingText()
        }
        publish(update.snapshot)
    }

    private fun handleEnter(activeEngine: InputEngine) {
        // The controller's published snapshot is the view shown to the user
        // and remains authoritative even when an adapter updates its mutable
        // property lazily.  Fall back to the adapter only for engines that
        // have not published a composing state yet.
        val before = state.snapshot
        if (!before.isComposing) {
            performEnterAction()
            publish(EngineSnapshot.Empty)
            return
        }

        val rawComposition = before.rawInput.ifBlank { before.composition }
        runCatching { activeEngine.reset() }
            .onFailure { if (engine === activeEngine) closeEngine() }
        commitRawComposition(rawComposition)
        publish(EngineSnapshot.Empty)
    }

    private fun commitLiteral(activeEngine: InputEngine, text: String) {
        if (text.isEmpty()) return
        if (state.modelRanked) selectCandidate(activeEngine, state.snapshot.highlightedIndex)
        if (state.snapshot.isComposing) {
            val before = state.snapshot
            if (before.candidates.isNotEmpty()) {
                selectCandidate(activeEngine, before.highlightedIndex)
            }
            flushUnconsumedComposition(activeEngine, state.snapshot)
        }
        connection.commitText(text)
        associations.clear()
        publish(rawEngineSnapshot)
    }

    private fun commitPaired(activeEngine: InputEngine, opening: String, closing: String) {
        if (opening.isEmpty() || closing.isEmpty()) return
        if (state.modelRanked) selectCandidate(activeEngine, state.snapshot.highlightedIndex)
        if (state.snapshot.isComposing) {
            val before = state.snapshot
            if (before.candidates.isNotEmpty()) selectCandidate(activeEngine, before.highlightedIndex)
            flushUnconsumedComposition(activeEngine, state.snapshot)
        }
        connection.commitPairedText(opening + closing, opening.length)
        associations.clear()
        publish(rawEngineSnapshot)
    }

    private fun performEnterAction() {
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val handled = EditorInputOptions.enterAction(editorInfo) != EnterAction.NEW_LINE &&
            connection.performEditorAction(action)
        if (!handled) connection.sendEnterKey()
    }

    private fun selectCandidate(activeEngine: InputEngine, visibleIndex: Int) {
        when (val route = routes.getOrNull(visibleIndex)) {
            is CandidateRoute.Engine -> {
                // Use the last published state rather than the adapter's
                // mutable property; native adapters may expose a stale
                // snapshot between callbacks.
                val learningShortcut = state.snapshot.rawInput
                apply(candidateWindow.select(activeEngine, route.reference, rawEngineSnapshot), learningShortcut)
            }
            is CandidateRoute.Personal -> {
                val reading = route.input.ifBlank { state.snapshot.rawInput }
                candidateWindow.clear()
                activeEngine.reset()
                val committed = commitPredictableText(route.text)
                if (committed && language == InputLanguage.CHINESE && activeEngine is CompositionEditingEngine &&
                    connection is ReconversionEditorConnection) recentComposition.remember(reading, route.text)
                if (committed && privacy.personalizationAllowed) {
                    runCatching {
                        personalization.recordUse(route.id, learningAllowed = privacy.learningAllowed)
                    }.onFailure { learningFailureCount++ }
                }
                publish(EngineSnapshot.Empty)
            }
            is CandidateRoute.Association -> {
                if (!associationsAllowed() || rawEngineSnapshot.isComposing) return
                val selected = associations.selection(route.id) ?: return
                candidateWindow.clear()
                val committed = commitPredictableText(selected.commitText)
                // ADR 0014: an explicit, adapter-accepted click learns the word
                // through the ordinary encrypted channel with the session's
                // learning flag.  The Chinese table carries no reading, so the
                // value itself is the shortcut: it feeds frequency reranking
                // and phrase management but cannot surface through pinyin
                // prefix matching until the table gains a reading column.
                if (committed && privacy.personalizationAllowed) {
                    runCatching {
                        personalization.learn(
                            shortcut = selected.text,
                            value = selected.text,
                            language = language,
                            learningAllowed = privacy.learningAllowed,
                        )
                    }.onFailure { learningFailureCount++ }
                }
                publish(EngineSnapshot.Empty)
            }
            is CandidateRoute.Kaomoji -> {
                if (language != InputLanguage.CHINESE || !privacy.suggestionsAllowed ||
                    route.personal && !privacy.personalizationAllowed) return
                val stillAvailable = runCatching {
                    kaomojiCandidates.suggestions(state.snapshot.rawInput, privacy.personalizationAllowed)
                        .any { it.id == route.id && it.text == route.text }
                }.getOrDefault(false)
                if (!stillAvailable) { publish(rawEngineSnapshot); return }
                candidateWindow.clear()
                activeEngine.reset()
                commitPredictableText(route.text)
                publish(EngineSnapshot.Empty)
            }
            null -> Unit
        }
    }

    private fun handleSensitive(command: InputCommand) {
        handleEditorFallback(command)
    }

    private fun handleEditorFallback(command: InputCommand) {
        associations.clear()
        when (command) {
            is InputCommand.Text -> connection.commitText(command.value)
            is InputCommand.LiteralText -> connection.commitText(command.value)
            is InputCommand.PairedText -> connection.commitPairedText(command.opening + command.closing, command.opening.length)
            InputCommand.Backspace -> connection.deleteBeforeCursor()
            InputCommand.Space -> connection.commitText(" ")
            InputCommand.Enter -> performEnterAction()
            is InputCommand.SelectCandidate,
            is InputCommand.SelectReading,
            is InputCommand.ChangeCandidatePage,
            InputCommand.ReconvertLast,
            InputCommand.UndoSelection,
            InputCommand.SelectSyllable,
            -> Unit
        }
        publish(EngineSnapshot.Empty)
    }

    private fun publish(engineSnapshot: EngineSnapshot) {
        rawEngineSnapshot = engineSnapshot
        val browsed = candidateWindow.snapshot(engineSnapshot)
        val composed = personalPaging.publish(browsed, personalization, language, privacy.personalizationAllowed) { text ->
            runCatching { (engine as? CandidateTextNormalizer)?.normalizeCandidateText(text) ?: text }.getOrNull()
        }
        if (!associationsAllowed()) associations.clear()
        if (composed.isComposing) associations.hide()
        val associated = if (!composed.isComposing && composed.candidates.isEmpty() && associations.candidates.isNotEmpty())
            composed.copy(candidates = associations.candidates) else composed
        val visibleSnapshot = withKaomoji(associated)
        // The update snapshot is the authoritative view for this event.  Do
        // not resolve engine candidates through the engine's mutable
        // `snapshot` property: adapters may publish that property lazily (or
        // expose a stale value after a native callback), which would make a
        // visible candidate unselectable or select the wrong index.
        rebuildRoutes(visibleSnapshot)
        state = InputSessionState(language, privacy, visibleSnapshot, languagePackKey, engine?.descriptor,
            canReconvert = recentComposition.available && !visibleSnapshot.isComposing,
            candidateRevision = state.candidateRevision + 1)
        onStateChanged(state)
    }

    private fun withKaomoji(snapshot: EngineSnapshot): EngineSnapshot {
        if (language != InputLanguage.CHINESE || languagePackKey != null || !snapshot.isComposing ||
            !privacy.suggestionsAllowed || engine?.descriptor?.id != "rime.luna-pinyin") return snapshot
        val input = snapshot.rawInput
        if (input.length !in 2..32 || input.any { it !in 'a'..'z' }) return snapshot
        val seen = snapshot.candidates.mapTo(HashSet()) { it.text }
        val extra = runCatching { kaomojiCandidates.suggestions(input, privacy.personalizationAllowed) }
            .getOrDefault(emptyList())
            .take(4).filter { it.text.isNotEmpty() && seen.add(it.text) }
            .map { Candidate("kaomoji:${it.id}", it.text) }
        return if (extra.isEmpty()) snapshot else snapshot.copy(candidates = snapshot.candidates + extra)
    }

    private fun commitUnconsumedKey(
        activeEngine: InputEngine,
        updateSnapshot: EngineSnapshot,
        beforeSnapshot: EngineSnapshot,
        text: String,
    ) {
        // Prefer the snapshot returned with this key event.  It is the only
        // state that is guaranteed to describe the engine after the event;
        // some adapters expose a stale property while returning an update.
        val snapshot = updateSnapshot.takeIf(EngineSnapshot::isComposing)
            ?: beforeSnapshot.takeIf(EngineSnapshot::isComposing)
        associations.clear()
        flushUnconsumedComposition(activeEngine, snapshot, beforeSnapshot)
        connection.commitText(text)
    }

    private fun flushUnconsumedComposition(
        activeEngine: InputEngine,
        snapshot: EngineSnapshot?,
        beforeSnapshot: EngineSnapshot = EngineSnapshot.Empty,
    ) {
        val effectiveSnapshot = snapshot?.takeIf(EngineSnapshot::isComposing)
            ?: beforeSnapshot.takeIf(EngineSnapshot::isComposing)
            ?: return
        val rawComposition = fallbackComposition(effectiveSnapshot)
        runCatching { activeEngine.reset() }
            .onFailure { if (engine === activeEngine) closeEngine() }
        commitRawComposition(rawComposition)
        publish(EngineSnapshot.Empty)
    }

    private fun reflectsCharacter(
        before: EngineSnapshot,
        after: EngineSnapshot,
        text: String,
    ): Boolean = text.isNotEmpty() && compositionInput(after) == compositionInput(before) + text

    private fun reflectsBackspace(before: EngineSnapshot, after: EngineSnapshot): Boolean {
        val previous = compositionInput(before)
        if (previous.isEmpty()) return false
        return compositionInput(after) == previous.dropLast(1)
    }

    private fun compositionInput(snapshot: EngineSnapshot): String =
        snapshot.rawInput.ifEmpty { snapshot.composition }

    private fun fallbackComposition(snapshot: EngineSnapshot): String =
        // Once a segment is selected, raw input still contains its original
        // reading. Falling back to that reading would discard the user's choice.
        if (snapshot.canUndoSelection && snapshot.composition.isNotEmpty()) snapshot.composition
        else snapshot.rawInput.ifBlank { snapshot.composition }

    private fun commitRawComposition(rawComposition: String) {
        associations.clear()
        // Clear the composing span first so finishComposingText cannot commit
        // the preedit a second time when the raw text is submitted explicitly.
        connection.setComposingText("")
        connection.finishComposingText()
        if (rawComposition.isNotEmpty()) connection.commitText(rawComposition)
    }

    private fun rebuildRoutes(snapshot: EngineSnapshot) {
        routes = snapshot.candidates.map { candidate ->
            if (candidate.kind == CandidateKind.NEXT_WORD) {
                CandidateRoute.Association(candidate.id)
            } else if (candidate.id.startsWith("kaomoji:")) {
                val id = candidate.id.removePrefix("kaomoji:")
                CandidateRoute.Kaomoji(id, candidate.text, id.startsWith("custom:"))
            } else if (candidate.id.startsWith("personal:")) {
                CandidateRoute.Personal(candidate.id.removePrefix("personal:"), candidate.text, candidate.input)
            } else {
                candidateWindow.route(candidate.id)?.let(CandidateRoute::Engine)
            }
        }
    }

    private fun startEngine(candidate: InputEngine): EngineSnapshot? = runCatching {
        candidate.start(
            EditorContext(language, privacy.isSensitive, privacy.learningAllowed, editorInfo.packageName,
                predictionsAllowed = privacy.predictionsAllowed),
        )
    }.getOrNull()

    private fun closeEngine() {
        associations.clear()
        candidateWindow.clear()
        personalPaging.clear()
        invalidateReconversion(publishState = false)
        engine?.let(::closeSafely)
        engine = null
    }

    private fun closeSafely(candidate: InputEngine) {
        runCatching { candidate.close() }
    }

    private fun recoverFromEngineFailure(failedEngine: InputEngine) {
        val snapshot = state.snapshot.takeIf(EngineSnapshot::isComposing)
        if (engine === failedEngine) closeEngine() else closeSafely(failedEngine)
        if (snapshot != null) {
            val raw = fallbackComposition(snapshot)
            commitRawComposition(raw)
        } else {
            connection.clearComposingText()
        }
        publish(EngineSnapshot.Empty)
    }

    private sealed interface CandidateRoute {
        data class Engine(val reference: CandidateWindow.Route) : CandidateRoute

        data class Personal(val id: String, val text: String, val input: String) : CandidateRoute
        data class Association(val id: String) : CandidateRoute
        data class Kaomoji(val id: String, val text: String, val personal: Boolean) : CandidateRoute
    }

}

data class InputSessionState(
    val language: InputLanguage = InputLanguage.CHINESE,
    val privacy: SessionPrivacy = SessionPrivacy(
        isSensitive = false,
        suggestionsAllowed = true,
        learningAllowed = false,
        reason = dev.zeroinput.ime.core.privacy.PrivacyReason.USER_DISABLED,
    ),
    val snapshot: EngineSnapshot = EngineSnapshot.Empty,
    val languagePackKey: String? = null,
    val engineDescriptor: EngineDescriptor? = null,
    val canReconvert: Boolean = false,
    val candidateRevision: Long = 0,
    val modelRanked: Boolean = false,
)
