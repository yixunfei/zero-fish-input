package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAction
import dev.zeroinput.ai.api.AiRequest
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class AiStandalonePolicyTest {
    private val request = AiRequest(null, AiAction.ASK, "public question")

    @Test fun standaloneDraftNeverGrantsPersonalization() {
        val policy = AiStandalonePolicy.generationPolicy(AiConfiguration(enabled = true, networkAllowed = true), true)
        policy.check(request)
        assertFalse(policy.personalizationAllowed)
        assertFalse(policy.sensitiveEditor)
    }

    @Test fun privacyTighteningAndLockingRejectIndependentRequests() {
        for (learning in listOf(false, true)) for (incognito in listOf(false, true)) for (unlocked in listOf(false, true)) {
            val allowed = AiStandalonePolicy.allows(learning, incognito, unlocked)
            assertEquals(learning && !incognito && unlocked, allowed)
            if (!allowed) assertThrows(dev.zeroinput.ai.api.AiProviderError.Policy::class.java) {
                AiStandalonePolicy.generationPolicy(AiConfiguration(enabled = true, networkAllowed = true), allowed).check(request)
            }
        }
    }

    @Test fun standaloneRequestStillRequiresBothSavedNetworkSwitches() {
        for (enabled in listOf(false, true)) for (network in listOf(false, true)) {
            if (enabled && network) continue
            assertThrows(dev.zeroinput.ai.api.AiProviderError.Policy::class.java) {
                AiStandalonePolicy.generationPolicy(AiConfiguration(enabled = enabled, networkAllowed = network), true).check(request)
            }
        }
    }
}
