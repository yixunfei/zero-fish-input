package dev.zeroinput.ime

import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.PreparedInputEngine
import dev.zeroinput.ime.core.privacy.*
import org.junit.Assert.*
import org.junit.Test

class EngineWarmupResultDeliveryTest {
    @Test fun activeCompositionReleasesPreparedEngineUntilNextWarmup() {
        val queued = mutableListOf<Runnable>()
        var ready = false
        var delivered = 0
        val delivery = EngineWarmupResultDelivery({ queued += it; true }, { ready },
            removePosted = { queued.remove(it) }) { delivered++ }
        val first = TrackingEngine()
        delivery.offer(prepared(first))
        queued.removeAt(0).run()
        assertEquals(1, first.closes)
        assertEquals(0, delivered)
        ready = true
        delivery.resume()
        assertTrue(queued.isEmpty())
        assertEquals(0, delivered)
        delivery.close()
        assertEquals(1, first.closes)
    }

    @Test fun closingOrRejectedPostReleasesDeferredNativeOwnership() {
        val queued = mutableListOf<Runnable>()
        val engine = TrackingEngine()
        val delivery = EngineWarmupResultDelivery({ queued += it; true }, { false },
            removePosted = { queued.remove(it) }) { error("Stale delivery") }
        delivery.offer(prepared(engine))
        queued.removeAt(0).run()
        delivery.close()
        delivery.resume()
        assertEquals(1, engine.closes)
        assertTrue(queued.isEmpty())
        val rejected = TrackingEngine()
        EngineWarmupResultDelivery({ false }) { error("Rejected") }.offer(prepared(rejected))
        assertEquals(1, rejected.closes)
    }

    @Test fun closeRemovesQueuedDeliveryAndReleasesItsPreparedEngine() {
        val queued = mutableListOf<Runnable>()
        val engine = TrackingEngine()
        val delivery = EngineWarmupResultDelivery({ queued += it; true },
            removePosted = { queued.remove(it) }) { error("Closed delivery") }

        delivery.offer(prepared(engine))
        delivery.close()

        assertTrue(queued.isEmpty())
        assertEquals(1, engine.closes)
    }

    private fun prepared(engine: InputEngine): EngineWarmupResult.Prepared {
        val privacy = SessionPrivacy(false, true, false, PrivacyReason.USER_DISABLED)
        val request = EngineWarmupRequest(1, InputLanguage.CHINESE, null, null, privacy)
        return EngineWarmupResult.Prepared(1, request, PreparedInputEngine(InputLanguage.CHINESE, null, null,
            privacy, EngineSnapshot.Empty, engine))
    }

    private class TrackingEngine : InputEngine {
        var closes = 0
        override val descriptor = EngineDescriptor("fixture", "Fixture", "1", setOf(InputLanguage.CHINESE))
        override val snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = snapshot
        override fun handle(key: EngineKey) = EngineUpdate(snapshot, consumed = false)
        override fun selectCandidate(index: Int) = EngineUpdate(snapshot, consumed = false)
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot, consumed = false)
        override fun reset() = snapshot
        override fun close() { closes++ }
    }
}
