package dev.zeroinput.engine.api

/** Shared observable contract; callers supply an owned, ready engine with public fixture data. */
object InputEngineContract {
    fun verify(engine: InputEngine, language: InputLanguage, input: String, expected: String, committed: String = expected) {
        val context = EditorContext(language, false, true, null)
        engine.use {
            check(engine.start(context) == EngineSnapshot.Empty)
            val initial = engine.snapshot
            input.forEach { character ->
                val update = engine.handle(EngineKey.Character(character.toString()))
                check(update.consumed && update.committedText.isEmpty())
                check(update.snapshot == engine.snapshot)
            }
            check(initial == EngineSnapshot.Empty) // Published snapshots cannot mutate in place.
            val composed = engine.snapshot
            check(composed.rawInput == input && composed.isComposing)
            check(composed.candidates.any { it.text == expected })
            check(engine.selectCandidate(-1).snapshot == composed)
            check(engine.selectCandidate(Int.MAX_VALUE).snapshot == composed)
            check(engine.changePage(PageDirection.NEXT).snapshot.rawInput == input)
            check(engine.changePage(PageDirection.PREVIOUS).snapshot.rawInput == input)
            engine.reset()
            input.forEach { engine.handle(EngineKey.Character(it.toString())) }
            val index = engine.snapshot.candidates.indexOfFirst { it.text == expected }
            check(index >= 0)
            val selected = engine.selectCandidate(index)
            check(selected.committedText == committed && !selected.snapshot.isComposing)
            check(engine.reset() == EngineSnapshot.Empty)
            engine.handle(EngineKey.Character("n"))
            check(!engine.handle(EngineKey.Backspace).snapshot.isComposing)
            engine.handle(EngineKey.Character("n"))
            check(engine.handle(EngineKey.Enter).committedText.isNotEmpty())
            engine.handle(EngineKey.Character("n"))
            check(engine.handle(EngineKey.Space).committedText.isNotEmpty())
            check(engine.start(context) == EngineSnapshot.Empty)
        }
        // close releases ownership; no calls after close are part of this contract.
    }
}
