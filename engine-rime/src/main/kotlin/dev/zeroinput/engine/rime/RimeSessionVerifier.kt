package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage

/** Worker-only probe using public fixture text, before any editor owns the session. */
internal object RimeSessionVerifier {
    fun verify(engine: InputEngine, layout: ChineseKeyboardLayout = ChineseKeyboardLayout.FULL) {
        try {
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            val input = if (layout == ChineseKeyboardLayout.NINE_KEY) "64426" else "nihao"
            for (character in input) {
                val update = engine.handle(EngineKey.Character(character.toString()))
                check(update.consumed && update.committedText.isEmpty()) { "Rime conversion probe failed" }
            }
            val index = engine.snapshot.candidates.indexOfFirst { it.text == "你好" }
            check(index >= 0) { "Rime dictionary probe failed" }
            val selected = engine.selectCandidate(index)
            check(selected.committedText == "你好" && !selected.snapshot.isComposing) {
                "Rime selection probe failed"
            }
        } finally {
            engine.reset()
        }
    }
}
