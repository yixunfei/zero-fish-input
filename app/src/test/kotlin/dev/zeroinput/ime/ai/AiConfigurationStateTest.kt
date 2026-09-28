package dev.zeroinput.ime.ai

import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class AiConfigurationStateTest {
    @Test fun lateReadCannotRestoreConfigurationAfterRevocation() {
        val state = AiConfigurationState()
        val read = state.current()
        state.revoke()
        assertFalse(state.publish(read, AiConfiguration(enabled = true, networkAllowed = true)))
        assertNull(state.snapshot())
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
