package dev.zeroinput.ime.core.stego

/** Main-thread state owner. Cancellation revokes publication without releasing a running worker. */
class StegoOperationGate(private val now: () -> Long) {
    enum class Phase { CLOSED, EDITING, WORKING, PREVIEW, RESULT }
    var phase = Phase.CLOSED
        private set
    private var revision = 0L
    private var deadline = 0L

    fun open() { revision++; phase = Phase.EDITING; touch() }
    fun close() { revision++; phase = Phase.CLOSED; deadline = 0 }
    fun touch() { if (phase == Phase.EDITING) deadline = now() + 120_000 }
    fun current(): Boolean {
        if (phase != Phase.CLOSED && now() >= deadline) close()
        return phase != Phase.CLOSED
    }
    fun begin(): Long? {
        if (!current() || phase != Phase.EDITING) return null
        phase = Phase.WORKING
        return ++revision
    }
    fun accepts(token: Long): Boolean = current() && token == revision && phase == Phase.WORKING
    fun complete(token: Long, result: Boolean): Boolean {
        if (!accepts(token)) return false
        phase = if (result) Phase.RESULT else Phase.PREVIEW
        deadline = now() + 30_000
        return true
    }
    fun fail(token: Long) { if (accepts(token)) { phase = Phase.EDITING; touch() } }
    fun consume(expected: Phase): Boolean {
        if (!current() || phase != expected) return false
        close()
        return true
    }
    fun edit() { if (current() && phase != Phase.WORKING) { revision++; phase = Phase.EDITING; touch() } }
}
