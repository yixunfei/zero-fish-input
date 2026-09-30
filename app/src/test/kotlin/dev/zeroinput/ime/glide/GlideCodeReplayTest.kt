package dev.zeroinput.ime.glide

import dev.zeroinput.ime.core.InputCommand
import org.junit.Assert.*
import org.junit.Test

class GlideCodeReplayTest {
    @Test fun synchronousRevocationWhileFlushingPreventsAnyNewLetters() {
        var valid = true
        val sent = mutableListOf<InputCommand>()
        val queue = ArrayDeque<Runnable>()
        val replay = GlideCodeReplay("hello", true, { valid },
            { sent += it; valid = false }, { queue.add(it); true }, { queue.remove(it) })
        replay.start(true)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf(InputCommand.Space), sent)
    }

    @Test fun synchronousRevocationFromFinalLetterPreventsEnglishCommit() {
        var valid = true
        val sent = mutableListOf<InputCommand>()
        val queue = ArrayDeque<Runnable>()
        val replay = GlideCodeReplay("hi", true, { valid },
            { sent += it; if (it == InputCommand.Text("i")) valid = false },
            { queue.add(it); true }, { queue.remove(it) })
        replay.start(false)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf(InputCommand.Text("h"), InputCommand.Text("i")), sent)
    }

    @Test fun chineseReadingsAppendWithoutSelectingOrCommittingPartialComposition() {
        val sent = mutableListOf<InputCommand>()
        val queue = ArrayDeque<Runnable>()
        val replay = GlideCodeReplay("nihao", false, { true }, { sent += it },
            { queue.add(it); true }, { queue.remove(it) })
        replay.start(true)
        queue.removeFirst().run()
        assertEquals(4, sent.size)
        queue.removeFirst().run()
        assertEquals("nihao".map { InputCommand.Text(it.toString()) }, sent)
    }

    @Test fun cancelledOrRejectedSchedulingSendsNothing() {
        val sent = mutableListOf<InputCommand>()
        val rejected = GlideCodeReplay("hello", true, { true }, { sent += it }, { false }, {})
        rejected.start(false)
        assertTrue(sent.isEmpty())
        val queue = ArrayDeque<Runnable>()
        val cancelled = GlideCodeReplay("hello", true, { true }, { sent += it },
            { queue.add(it); true }, { queue.remove(it) })
        cancelled.start(false)
        cancelled.cancel()
        assertTrue(queue.isEmpty())
        assertTrue(sent.isEmpty())
    }
}
