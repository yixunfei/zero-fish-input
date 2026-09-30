package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.engine.api.CandidateTextNormalizer
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.EngineCapability
import dev.zeroinput.engine.api.ReadingSelectionEngine
import dev.zeroinput.engine.api.CompositionEditingEngine

internal class RimeInputEngine(
    schemaId: String = "zeroinput_pinyin",
    private val options: ChineseInputOptions = ChineseInputOptions(),
    private val onNativeFailure: (Throwable) -> Unit = {},
    private val onClosed: () -> Unit = {},
    private val nineKeyReadings: NineKeyReadings? = null,
) : InputEngine, CandidateTextNormalizer, ReadingSelectionEngine, CompositionEditingEngine {
    private var sessionId = NativeRimeBridge.nativeCreateSession(schemaId).also {
        check(it != 0L) { "Unable to create a Rime session" }
    }
    private var currentSnapshot = EngineSnapshot.Empty
    private var hasFixedSelection = false
    private val readingHistory = ArrayDeque<Pair<String, String>>()
    private val selectedIndices = mutableListOf<SelectedSegment>()
    private var selectedInput = ""
    private var pageNumber = 0
    private var caretPosition = 0
    private var selectingSyllable = false

    override val descriptor = Descriptor

    override val snapshot: EngineSnapshot
        get() = currentSnapshot

    override fun start(context: EditorContext): EngineSnapshot = nativeCall {
        NativeRimeBridge.nativeSetOptions(sessionId, options.script == ChineseScript.SIMPLIFIED, !options.chinesePunctuation)
        NativeRimeBridge.nativeClearComposition(sessionId)
        hasFixedSelection = false
        readingHistory.clear()
        clearSelectionHistory()
        readUpdate(false)
        currentSnapshot
    }

    override fun handle(key: EngineKey): EngineUpdate = nativeCall {
        if (key == EngineKey.Enter) {
            val raw = currentSnapshot.rawInput
            return if (raw.isEmpty()) EngineUpdate(currentSnapshot, consumed = false)
                else EngineUpdate(reset(), committedText = raw, learnable = false)
        }
        if (key == EngineKey.Space && currentSnapshot.candidates.isNotEmpty()) {
            return selectCandidate(currentSnapshot.highlightedIndex)
        }
        // Selecting a smaller candidate temporarily moves Rime's caret. Ordinary
        // typing/deletion resumes at the end of the visible preedit, not at that
        // hidden boundary where repeated Backspace would eventually do nothing.
        if (selectingSyllable) {
            NativeRimeBridge.nativeProcessKey(sessionId, KEY_END, 0)
            selectingSyllable = false
            readUpdate(false)
        }
        val input = currentSnapshot.rawInput
        if (input.length >= 128 && key is EngineKey.Character && key.text.any {
            it.isLetter() || it == '\'' ||
                options.effectiveDoublePinyinScheme == dev.zeroinput.engine.api.DoublePinyinScheme.MICROSOFT && it == ';' ||
                options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY && it in '2'..'9'
        }) {
            return EngineUpdate(currentSnapshot, consumed = false)
        }
        val reading = committedReading(currentSnapshot.highlightedIndex)
        if (key == EngineKey.Backspace && selectedIndices.isNotEmpty() && input == selectedInput) return undoSelection()
        if (key == EngineKey.Backspace) {
            val previous = readingHistory.lastOrNull()
            if (previous != null && currentSnapshot.rawInput == previous.second) {
                val consumed = NativeRimeBridge.nativeSetInput(sessionId, previous.first)
                if (consumed) readingHistory.removeLast()
                return readUpdate(consumed)
            }
        }
        val (keyCode, modifiers) = when (key) {
            is EngineKey.Character -> key.text.singleOrNull()?.lowercaseChar()?.code?.let { it to 0 }
                ?: return EngineUpdate(currentSnapshot, consumed = false)
            EngineKey.Backspace -> KEY_BACKSPACE to 0
            EngineKey.Space -> KEY_SPACE to 0
            EngineKey.Enter -> error("Enter is handled before native key dispatch")
        }
        val consumed = NativeRimeBridge.nativeProcessKey(sessionId, keyCode, modifiers)
        readUpdate(consumed).copy(committedInput = reading.ifBlank { input }, learnable = reading.isNotEmpty())
    }

    override fun selectCandidate(index: Int): EngineUpdate = nativeCall {
        if (index !in currentSnapshot.candidates.indices) return EngineUpdate(currentSnapshot, consumed = false)
        val input = currentSnapshot.rawInput
        val absoluteIndex = pageNumber * options.candidatePageSize + index
        val reading = committedReading(index)
        val segment = SelectedSegment(absoluteIndex, caretPosition, canonicalReading(currentSnapshot.candidates[index].comment))
        val consumed = NativeRimeBridge.nativeSelectAbsoluteCandidate(sessionId, absoluteIndex)
        if (consumed) {
            selectingSyllable = false
            hasFixedSelection = true
            readingHistory.clear()
            selectedInput = input
            if (selectedIndices.size < 64) selectedIndices += segment else clearSelectionHistory()
        }
        readUpdate(consumed).copy(committedInput = reading.ifBlank { input }, learnable = reading.isNotEmpty())
    }

    override fun restoreComposition(input: String): EngineUpdate = nativeCall {
        if (input.isEmpty() || input.length > 128) return EngineUpdate(currentSnapshot, consumed = false)
        val consumed = NativeRimeBridge.nativeSetInput(sessionId, input)
        selectingSyllable = false
        clearSelectionHistory()
        readingHistory.clear()
        hasFixedSelection = false
        readUpdate(consumed)
    }

    override fun undoSelection(): EngineUpdate = nativeCall {
        if (selectedIndices.isEmpty()) return EngineUpdate(currentSnapshot, consumed = false)
        selectedIndices.removeAt(selectedIndices.lastIndex)
        val input = currentSnapshot.rawInput
        NativeRimeBridge.nativeClearComposition(sessionId)
        NativeRimeBridge.nativeSetInput(sessionId, input)
        for (step in selectedIndices) {
            if (!NativeRimeBridge.nativeSetCaret(sessionId, step.caret) ||
                !NativeRimeBridge.nativeSelectAbsoluteCandidate(sessionId, step.index)) {
                NativeRimeBridge.nativeClearComposition(sessionId)
                NativeRimeBridge.nativeSetInput(sessionId, input)
                clearSelectionHistory()
                break
            }
        }
        hasFixedSelection = selectedIndices.isNotEmpty()
        selectingSyllable = false
        readingHistory.clear()
        readUpdate(true)
    }

    override fun selectSyllable(): EngineUpdate = nativeCall {
        if (!currentSnapshot.isComposing) return EngineUpdate(currentSnapshot, consumed = false)
        NativeRimeBridge.nativeProcessKey(sessionId, KEY_END, 0)
        val consumed = NativeRimeBridge.nativeProcessKey(sessionId, KEY_RIGHT, CONTROL_MASK)
        selectingSyllable = consumed
        readUpdate(consumed)
    }

    private fun committedReading(index: Int): String {
        val suffix = currentSnapshot.candidates.getOrNull(index)?.comment?.let(::canonicalReading).orEmpty()
        if (suffix.isEmpty() || selectedIndices.any { it.reading.isEmpty() }) return ""
        return selectedIndices.joinToString("") { it.reading } + suffix
    }

    private fun canonicalReading(value: String): String = value.filterNot { it == ' ' || it == '\'' }
        .takeIf { it.isNotEmpty() && it.length <= 128 && it.all { ch -> ch in 'a'..'z' } }.orEmpty()

    private data class SelectedSegment(val index: Int, val caret: Int, val reading: String)

    private fun clearSelectionHistory() { selectedIndices.clear(); selectedInput = "" }

    override fun selectReading(index: Int): EngineUpdate = nativeCall {
        val reading = currentSnapshot.readings.getOrNull(index) ?: return EngineUpdate(currentSnapshot, consumed = false)
        val before = currentSnapshot.rawInput
        val input = nineKeyReadings?.replace(before, reading)
            ?: return EngineUpdate(currentSnapshot, consumed = false)
        val consumed = NativeRimeBridge.nativeSetInput(sessionId, input)
        if (consumed) {
            if (readingHistory.size == 64) readingHistory.removeFirst()
            readingHistory.addLast(before to input)
        }
        readUpdate(consumed)
    }

    override fun changePage(direction: PageDirection): EngineUpdate = nativeCall {
        val consumed = NativeRimeBridge.nativeChangePage(
            sessionId,
            backwards = direction == PageDirection.PREVIOUS,
        )
        readUpdate(consumed)
    }

    internal fun browsePage(page: Int): EngineUpdate = nativeCall {
        if (page < 0) return EngineUpdate(currentSnapshot, consumed = false)
        val result = NativeRimeBridge.nativeCandidatePage(sessionId, page, options.candidatePageSize)
        if (result.texts.isEmpty()) return EngineUpdate(currentSnapshot, consumed = false)
        pageNumber = page
        currentSnapshot = currentSnapshot.copy(candidates = result.texts.mapIndexed { index, text ->
            val comment = result.comments.getOrElse(index) { "" }
            Candidate("rime:$index:$text", text, comment, input = canonicalReading(comment))
        }, highlightedIndex = 0, hasPreviousPage = page > 0, hasNextPage = result.hasNext)
        EngineUpdate(currentSnapshot)
    }

    override fun reset(): EngineSnapshot = nativeCall {
        NativeRimeBridge.nativeClearComposition(sessionId)
        selectingSyllable = false
        hasFixedSelection = false
        readingHistory.clear()
        clearSelectionHistory()
        currentSnapshot = EngineSnapshot.Empty
        currentSnapshot
    }

    override fun close() {
        selectingSyllable = false
        val id = sessionId
        sessionId = 0L
        currentSnapshot = EngineSnapshot.Empty
        if (id != 0L) {
            readingHistory.clear()
            clearSelectionHistory()
            try {
                NativeRimeBridge.nativeDestroySession(id)
            } finally {
                onClosed()
            }
        }
    }

    override fun normalizeCandidateText(text: String): String = nativeCall {
        NativeRimeBridge.nativeConvertText(text, options.script == ChineseScript.SIMPLIFIED)
    }

    private fun readUpdate(consumed: Boolean): EngineUpdate {
        val update = NativeRimeBridge.nativeReadUpdate(sessionId)
        pageNumber = update.pageNumber
        caretPosition = update.caretPosition
        if (update.rawInput.isEmpty()) {
            selectingSyllable = false
            hasFixedSelection = false
            readingHistory.clear()
            clearSelectionHistory()
        }
        currentSnapshot = EngineSnapshot(
            rawInput = update.rawInput,
            composition = update.composition,
            candidates = update.candidates.mapIndexed { index, text ->
                val comment = update.comments.getOrElse(index) { "" }
                Candidate("rime:$index:$text", text, comment, input = canonicalReading(comment))
            },
            highlightedIndex = update.highlightedIndex,
            hasPreviousPage = update.candidates.isNotEmpty() && update.pageNumber > 0,
            hasNextPage = update.candidates.isNotEmpty() && !update.lastPage,
            readings = if (hasFixedSelection) emptyList() else nineKeyReadings?.choices(update.rawInput, update.comments).orEmpty(),
            canUndoSelection = selectedIndices.isNotEmpty(),
            canSelectSyllable = update.rawInput.isNotEmpty(),
        )
        return EngineUpdate(currentSnapshot, update.committedText, consumed || update.committedText.isNotEmpty())
    }

    private inline fun <T> nativeCall(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        onNativeFailure(error)
        throw error
    }

    internal companion object {
        const val KEY_BACKSPACE = 0xff08
        const val KEY_RETURN = 0xff0d
        const val KEY_SPACE = 0x20
        const val KEY_END = 0xff57
        const val KEY_RIGHT = 0xff53
        const val CONTROL_MASK = 4

        val Descriptor = EngineDescriptor(
            id = "rime.luna-pinyin",
            displayName = "Rime 全拼",
            version = "1.13.1-adapter1",
            languages = setOf(InputLanguage.CHINESE),
            capabilities = setOf(EngineCapability.CHINESE_SCRIPT, EngineCapability.ABBREVIATED_PINYIN,
                EngineCapability.FUZZY_PINYIN, EngineCapability.PUNCTUATION_MODE, EngineCapability.CANDIDATE_PAGE_SIZE,
                EngineCapability.NINE_KEY_PINYIN, EngineCapability.TYPO_CORRECTION,
                EngineCapability.SEGMENT_SELECTION, EngineCapability.DOUBLE_PINYIN),
        )
    }
}
