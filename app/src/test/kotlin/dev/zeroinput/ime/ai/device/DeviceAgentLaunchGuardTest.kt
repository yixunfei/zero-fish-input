package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceAgentPolicy
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class DeviceAgentLaunchGuardTest {
    private val config = AiConfiguration(enabled = true, networkAllowed = true)
    private val policy = DeviceAgentPolicy()
    private fun guard() = DeviceAgentLaunchGuard(1_000, 7, config, policy)

    @Test fun launchIsSingleUseAndCannotBeReplayedAfterStop() {
        val ticket = guard()
        assertTrue(ticket.consume(1_001, 7, config, policy))
        assertFalse(ticket.consume(1_002, 7, config, policy))
        val stopped = guard()
        stopped.cancel()
        assertFalse(stopped.consume(1_002, 7, config, policy))
    }

    @Test fun elapsedDeadlineRejectsLaunchEvenBeforeTimerCallbackRuns() {
        assertTrue(guard().consume(10_999, 7, config, policy))
        assertFalse(guard().consume(11_000, 7, config, policy))
        assertFalse(guard().consume(20_000, 7, config, policy))
        assertFalse(guard().consume(999, 7, config, policy))
    }

    @Test fun configurationPolicyAndDataRevocationInvalidatePendingLaunch() {
        assertFalse(guard().consume(1_001, 8, config, policy))
        assertFalse(guard().consume(1_001, 7, null, policy))
        assertFalse(guard().consume(1_001, 7, config.copy(networkAllowed = false), policy))
        assertFalse(guard().consume(1_001, 7, config, policy.copy(allowScreenshots = true)))
    }
}
