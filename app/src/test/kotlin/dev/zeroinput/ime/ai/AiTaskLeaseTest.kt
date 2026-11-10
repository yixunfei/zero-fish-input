package dev.zeroinput.ime.ai

import org.junit.Assert.*
import org.junit.Test

class AiTaskLeaseTest {
    @Test fun noncesOwnersDeadlinesAndCompletionCannotGrantBackgroundApprovals() {
        var time = 0L; val lease = AiTaskLease { time }
        val first = lease.begin("first", true)
        assertTrue(lease.canDispatch(false)); assertTrue(lease.leave("first", false))
        assertFalse(lease.canDispatch(false)); assertTrue(lease.canDispatch(true)); assertFalse(lease.canApprove("first"))
        lease.enter("second"); lease.leave("first", false); assertTrue(lease.canApprove("second"))
        time = 120_000; assertFalse(lease.valid(first)); assertFalse(lease.canDispatch(true))
        val second = lease.begin("second", false); assertFalse(lease.valid(first)); assertTrue(lease.valid(second))
        assertTrue(lease.leave("second", true)); assertTrue(lease.canDispatch(false))
        assertFalse(lease.destroy("second")); assertFalse(lease.canDispatch(false))
        lease.enter("third"); lease.completed(); assertFalse(lease.canApprove("third")); assertFalse(lease.canDispatch(true))
        time += 120_000; assertFalse(lease.valid(second)); lease.revoke(); assertFalse(lease.valid())
    }
}
