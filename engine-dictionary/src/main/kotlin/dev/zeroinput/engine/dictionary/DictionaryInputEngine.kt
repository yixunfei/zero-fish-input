package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.PageDirection

internal class DictionaryInputEngine(
    private val dictionary: ReferenceDictionary,
    private val options: ChineseInputOptions,
) : InputEngine {
    override val descriptor = DictionaryEngineFactory.Descriptor
    override var snapshot = EngineSnapshot.Empty
        private set
    private var input = ""
    private var matches: List<String> = emptyList()
    private var page = 0
    private var closed = false

    override fun start(context: EditorContext): EngineSnapshot {
        check(!closed) { "Engine is closed" }
        return reset()
    }

    override fun handle(key: EngineKey): EngineUpdate {
        if (closed) return EngineUpdate(snapshot, consumed = false)
        return when (key) {
            is EngineKey.Character -> character(key.text)
            EngineKey.Backspace -> if (input.isEmpty()) EngineUpdate(snapshot, consumed = false) else {
                input = input.dropLast(1)
                refresh()
            }
            EngineKey.Space -> commit(if (input.isEmpty()) " " else "")
            EngineKey.Enter -> if (input.isEmpty()) EngineUpdate(snapshot, consumed = false)
                else commitRawInput()
        }
    }

    override fun selectCandidate(index: Int): EngineUpdate {
        if (closed) return EngineUpdate(snapshot, consumed = false)
        val text = snapshot.candidates.getOrNull(index)?.text ?: return EngineUpdate(snapshot, consumed = false)
        return EngineUpdate(reset(), committedText = text)
    }

    override fun changePage(direction: PageDirection): EngineUpdate {
        val available = if (direction == PageDirection.NEXT) snapshot.hasNextPage else snapshot.hasPreviousPage
        if (closed || !available) return EngineUpdate(snapshot, consumed = false)
        page += if (direction == PageDirection.NEXT) 1 else -1
        return publish()
    }

    override fun reset(): EngineSnapshot {
        input = ""
        matches = emptyList()
        page = 0
        snapshot = EngineSnapshot.Empty
        return snapshot
    }

    override fun close() { reset(); closed = true }

    private fun character(value: String): EngineUpdate {
        val ch = value.singleOrNull()?.lowercaseChar()
        if (ch != null && (ch in 'a'..'z' || ch == '\'')) {
            if (input.length == 64) return EngineUpdate(snapshot, consumed = false)
            input += ch
            return refresh()
        }
        val suffix = if (options.chinesePunctuation) punctuation[value] ?: value else value
        return commit(suffix)
    }

    private fun refresh(): EngineUpdate {
        page = 0
        matches = dictionary.lookup(input)
        return publish()
    }

    private fun publish(): EngineUpdate {
        snapshot = if (input.isEmpty()) EngineSnapshot.Empty else EngineSnapshot(
            rawInput = input,
            composition = input,
            candidates = matches.drop(page * options.candidatePageSize).take(options.candidatePageSize).mapIndexed { index, value ->
                Candidate("dictionary:${page * options.candidatePageSize + index}", value)
            },
            hasPreviousPage = page > 0,
            hasNextPage = (page + 1) * options.candidatePageSize < matches.size,
        )
        return EngineUpdate(snapshot)
    }

    private fun commit(suffix: String): EngineUpdate {
        val text = (snapshot.candidates.firstOrNull()?.text ?: input) + suffix
        return EngineUpdate(reset(), committedText = text)
    }

    private fun commitRawInput(): EngineUpdate {
        val raw = input
        return EngineUpdate(reset(), committedText = raw)
    }

    companion object {
        private val punctuation = mapOf("," to "，", "." to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；")
    }
}
