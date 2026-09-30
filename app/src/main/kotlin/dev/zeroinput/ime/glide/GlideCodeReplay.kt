package dev.zeroinput.ime.glide

import dev.zeroinput.ime.core.InputCommand

/** Small owner-thread slices; even a synchronous editor callback can revoke the next command. */
internal class GlideCodeReplay(
    inputCode: String,
    private val english: Boolean,
    isCurrent: () -> Boolean,
    send: (InputCommand) -> Unit,
    private val post: (Runnable) -> Boolean,
    private val remove: (Runnable) -> Unit,
) {
    private var code = inputCode
    private var valid: (() -> Boolean)? = isCurrent
    private var command: ((InputCommand) -> Unit)? = send
    private var index = 0
    private val step = Runnable { advance() }

    init { require(inputCode.length in 1..48) { "Invalid glide code size" } }

    fun start(flushEnglishComposition: Boolean) {
        if (!current()) { cancel(); return }
        if (english && flushEnglishComposition) command?.invoke(InputCommand.Space)
        if (!current() || !post(step)) cancel()
    }

    private fun advance() {
        repeat(minOf(4, code.length - index)) {
            if (!current()) { cancel(); return }
            command?.invoke(InputCommand.Text(code[index++].toString()))
        }
        if (!current()) { cancel(); return }
        if (index < code.length) {
            if (!post(step)) cancel()
        } else {
            if (english) command?.invoke(InputCommand.Space)
            cancel()
        }
    }

    private fun current() = valid?.invoke() == true
    fun cancel() {
        remove(step)
        code = ""
        command = null
        valid = null
    }
}
