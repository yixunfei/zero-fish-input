package dev.zeroinput.ime

import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.PreparedInputEngine
import dev.zeroinput.ime.core.privacy.*
import org.junit.Assert.*
import org.junit.Test

class EngineWarmupResultDeliveryTest {
    @Test fun activeCompositionRetainsNewestEngineUntilOwnerResumes() {
        val queued = mutableListOf<Runnable>()
        var ready = false
        var delivered = 0
        val delivery = EngineWarmupResultDelivery({ queued += it; true }, { ready }) { delivered++ }
        val first = TrackingEngine()
        val second = TrackingEngine()
        delivery.offer(prepared(first))
        queued.removeAt(0).run()
        assertEquals(0, first.closes)
        assertEquals(0, delivered)
        delivery.offer(prepared(second))
        assertEquals(1, first.closes)
        queued.removeAt(0).run()
        ready = true
        delivery.resume()
        delivery.resume()
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(1, delivered)
        assertEquals(1, second.closes)
        delivery.close()
        assertEquals(1, second.closes)
    }

    @Test fun closingOrRejectedPostReleasesDeferredNativeOwnership() {
        val queued = mutableListOf<Runnable>()
        val engine = TrackingEngine()
        val delivery = EngineWarmupResultDelivery({ queued += it; true }, { false }) { error("Stale delivery") }
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
