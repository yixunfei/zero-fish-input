package dev.zeroinput.ime.clipboard

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ClipboardSelectionReaderTest {
    @Test
    fun explicitSelectionBecomesABoundedDraftOnlyAfterDelivery() = withReader { reader, posts ->
        var draft: ClipboardImportRequest? = null
        val caller = Thread.currentThread()
        reader.read(7, { assertNotSame(caller, Thread.currentThread()); "fixture" }, { true }) { draft = it }
        next(posts).invoke()
        val result = checkNotNull(draft)
        assertNull(result.copyText())
        assertTrue(result.beginAuthentication())
        assertTrue(result.authorize())
        assertEquals("fixture", result.copyText())
        result.close()
        assertNull(result.copyText())
    }

    @Test fun onlyAnExplicitWordingReadCanOfferMarkerlessSelectionToTheDecryptDraft() = withReader { reader, posts ->
        val text = "江水浸云光，山径通幽处。"
        for (natural in listOf(false, true)) {
            reader.read(text.length, { text }, { true }, naturalWording = natural) { draft ->
                requireNotNull(draft).use {
                    assertFalse(it.allowsAiImport)
                    assertEquals(natural, it.allowsStegoImport)
                    assertEquals(if (natural) text else null, it.takeForStego()?.let(::String))
                }
            }
            next(posts).invoke()
        }
        val oversized = "江".repeat(5462)
        reader.read(oversized.length, { oversized }, { true }, naturalWording = true) { assertNull(it) }
        next(posts).invoke()
    }

    @Test
    fun emptyOrOversizedSelectionsNeverReadTheEditor() = withReader { reader, _ ->
        for (length in listOf(0, -1, 8193, Int.MAX_VALUE)) {
            var called = false
            reader.read(length, { error("Editor must not be read") }, { true }) {
                called = true
                assertNull(it)
            }
            assertTrue(called)
        }
    }

    @Test
    fun mismatchedMalformedOrMissingEditorResponsesCannotBecomeDrafts() = withReader { reader, posts ->
        for (text in listOf(null, "", "ab", "x".repeat(8193), "\u0000", "\uD800", " ")) {
            var called = false
            reader.read(1, { text }, { true }) { called = true; assertNull(it) }
            next(posts).invoke()
            assertTrue(called)
        }
    }

    @Test
    fun editorChangeAfterReadBeforeDeliveryDiscardsTheSelection() = withReader { reader, posts ->
        var active = true
        reader.read(7, { "fixture" }, { active }) { fail("Stale editor result delivered") }
        val delivery = next(posts)
        active = false
        delivery()
    }

    @Test
    fun cancellationDiscardsAResponseFromAnEditorThatIgnoresInterruption() = withReader { reader, posts ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exitRead = AtomicBoolean()
        reader.read(7, {
            entered.countDown()
            while (!exitRead.get()) {
                try { release.await(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* Simulate remote IPC. */ }
            }
            "fixture"
        }, { true }) { fail("Cancelled selection delivered") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        reader.cancel()
        exitRead.set(true)
        release.countDown()
        next(posts).invoke()
    }

    @Test
    fun cancelAfterReadBeforePostedDeliveryDropsTheDraft() = withReader { reader, posts ->
        reader.read(7, { "fixture" }, { true }) { fail("Cancelled selection delivered") }
        val delivery = next(posts)
        reader.cancel()
        delivery()
    }

    @Test
    fun closedWorkerReportsFailureWithoutReadingOrDeliveringAnOldDraft() = withReader { reader, _ ->
        reader.close()
        var called = false
        reader.read(7, { error("Closed worker read") }, { true }) { called = true; assertNull(it) }
        assertTrue(called)
    }

    private fun withReader(test: (ClipboardSelectionReader, LinkedBlockingQueue<() -> Unit>) -> Unit) {
        val posts = LinkedBlockingQueue<() -> Unit>()
        val reader = ClipboardSelectionReader({ posts.add(it) })
        try { test(reader, posts) } finally { reader.close() }
    }

    private fun next(posts: LinkedBlockingQueue<() -> Unit>): () -> Unit =
        checkNotNull(posts.poll(5, TimeUnit.SECONDS)) { "Selection delivery did not arrive" }
}
