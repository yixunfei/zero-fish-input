package dev.zeroinput.ime.ai

import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class AiConfigurationStateTest {
    @Test fun reloadingUnchangedConfigurationPreservesTheSubmittedSnapshot() {
        val state = AiConfigurationState()
        val profile = dev.zeroinput.userdata.AiProviderProfile("fixture", "Fixture", "https://example.test/v1",
            "public-fixture-key", listOf("text"), "text")
        val configuration = AiConfiguration(enabled = true, networkAllowed = true,
            providers = listOf(profile), selectedProviderId = profile.id)
        assertTrue(state.publish(state.current(), configuration))
        val submitted = state.snapshot()
        assertTrue(state.publish(state.current(), configuration.copy(providers = listOf(profile.copy()))))
        assertSame(submitted, state.snapshot())
    }

    @Test fun publishedSnapshotNeverRetainsCredentialsEvenWhenDisabled() {
        val state = AiConfigurationState()
        val profile = dev.zeroinput.userdata.AiProviderProfile("fixture", "Fixture", "https://example.test/v1",
            "public-fixture-key", listOf("text"), "text")
        val configuration = AiConfiguration(providers = listOf(profile), selectedProviderId = "fixture")
        assertTrue(state.publish(state.current(), configuration))
        val snapshot = checkNotNull(state.snapshot())
        assertTrue(snapshot.providers.all { it.apiKey.isEmpty() })
        assertTrue(snapshot.hasActiveKey())
        state.revoke()
        assertNull(state.snapshot())
    }

    @Test fun lateReadCannotRestoreConfigurationAfterRevocation() {
        val state = AiConfigurationState()
        val read = state.current()
        state.revoke()
        assertFalse(state.publish(read, AiConfiguration(enabled = true, networkAllowed = true)))
        assertNull(state.snapshot())
    }

    @Test fun editingRecoveryKeepsNetworkRevokedAndRejectsAnObsoleteRead() {
        val state = AiConfigurationState()
        val configuration = AiConfiguration(enabled = true, networkAllowed = true)
        val token = state.revoke()
        val recovered = checkNotNull(state.forEditing(token, configuration))
        assertFalse(recovered.enabled)
        assertFalse(recovered.networkAllowed)
        assertNull(state.snapshot())
        assertFalse(state.publish(token, configuration))
        state.revoke()
        assertNull(state.forEditing(token, configuration))
    }

    @Test fun explicitSaveAndCredentialChangeReplacePreviouslySubmittedSnapshots() {
        val state = AiConfigurationState()
        val configuration = AiConfiguration(enabled = true, networkAllowed = true)
        state.publish(state.current(), configuration)
        val old = state.snapshot()
        val token = state.revoke()
        assertTrue(state.publish(token, configuration.copy(), explicitControl = true))
        assertNotSame(old, state.snapshot())
        state.publish(token, configuration.copy(networkAllowed = false))
        assertFalse(checkNotNull(state.snapshot()).networkAllowed)
    }

    @Test fun lateWriteCannotReplaceNewerDisabledConfiguration() {
        val state = AiConfigurationState()
        val firstSave = state.revoke()
        val secondSave = state.revoke()
        val disabled = AiConfiguration()
        assertTrue(state.publish(secondSave, disabled, explicitControl = true))
        assertFalse(state.publish(firstSave, disabled.copy(enabled = true, networkAllowed = true)))
        assertSame(disabled, state.snapshot())
    }

    @Test fun editorInvalidationDoesNotDiscardConfigurationBeingLoaded() {
        val state = AiConfigurationState()
        val editorGeneration = AiDataGeneration()
        val read = state.current()
        editorGeneration.invalidate()
        val configuration = AiConfiguration(enabled = true)
        assertTrue(state.publish(read, configuration))
        assertSame(configuration, state.snapshot())
    }
}
