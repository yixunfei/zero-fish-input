package dev.zeroinput.ime.ai.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class PageScreenshotBrokerTest {
    @Test fun `capture without an attached source fails closed`() {
        val broker = PageScreenshotBroker(
            Executor { it.run() }, { it(); true }, { true },
            LocalPageOcr { _, _ -> error("OCR must not run") },
        )
        var result: PageTextSnapshot? = PageTextSnapshot(listOf("stale"), false)
        broker.capture("public.app") { _, value -> result = value }
        assertNull(result)
    }

    @Test fun `disabled policy prevents screenshot source access`() {
        var reads = 0
        var enabled = false
        val broker = PageScreenshotBroker(
            Executor { it.run() }, { it(); true }, { enabled },
            LocalPageOcr { _, _ -> error("OCR must not run") },
        )
        broker.attach(PageScreenshotSource { _, _, _ -> reads++ })
        var result: PageTextSnapshot? = null
        broker.capture("public.app") { _, value -> result = value }
        assertEquals(0, reads)
        assertNull(result)
        assertTrue(!enabled)
    }

    @Test fun `invalidation rejects a late screenshot callback`() {
        var callback: ((android.graphics.Bitmap?) -> Unit)? = null
        val broker = PageScreenshotBroker(
            Executor { it.run() }, { it(); true }, { true },
            LocalPageOcr { _, _ -> error("OCR must not run") },
        )
        broker.attach(PageScreenshotSource { _, _, next -> callback = next })
        var result: PageTextSnapshot? = null
        broker.capture("public.app") { _, value -> result = value }
        broker.invalidate()
        callback?.invoke(null)
        assertNull(result)
    }
}
