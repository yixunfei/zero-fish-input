package dev.zeroinput.ime.ai.device

import android.app.Application
import androidx.appcompat.view.ContextThemeWrapper
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DeviceInteractionTest {
    @Test fun failureExpiryRejectionAndSuccessHaveDistinctUserLabels() {
        assertEquals(4, DeviceActionResultStatus.entries.map(DeviceTaskTimelineView::resultLabel).toSet().size)
        val text = DeviceAgentDescriptions()
        assertNotEquals(text.status(DeviceAgentState.FAILED), text.status(DeviceAgentState.PAUSED))
        assertNotEquals(text.status(DeviceAgentState.REVOKED), text.status(DeviceAgentState.PAUSED))
    }

    @Test fun capabilityProjectionCannotConvertUnsupportedOrDisabledToReady() {
        for (permission in listOf(false, true)) for (enabled in listOf(false, true)) {
            assertEquals(DeviceCapabilityOverview.Availability.UNSUPPORTED,
                DeviceCapabilityOverview.availability(enabled, false, permission))
        }
        assertEquals(DeviceCapabilityOverview.Availability.DISABLED, DeviceCapabilityOverview.availability(false, true, true))
        assertEquals(DeviceCapabilityOverview.Availability.PERMISSION_REQUIRED, DeviceCapabilityOverview.availability(true, true, false))
        assertEquals(DeviceCapabilityOverview.Availability.READY, DeviceCapabilityOverview.availability(true, true, true))
    }

    @Test fun appSearchMatchesLocalLabelsAndPackagesWithoutExpandingInputList() {
        val choices = listOf(DeviceAgentAppChoice("Public Notes", "public.notes"), DeviceAgentAppChoice("Camera", "public.camera"))
        assertEquals(listOf(choices[0]), DeviceAppChoiceDialog.filter(choices, " NOTES "))
        assertEquals(listOf(choices[1]), DeviceAppChoiceDialog.filter(choices, "public.camera"))
        assertTrue(DeviceAppChoiceDialog.filter(choices, "absent").isEmpty())
    }

    @Test fun repeatedStateDoesNotDuplicateRowsAndHistoryStaysBounded() {
        val view = DeviceTaskTimelineView(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ZeroInput))
        val event = DeviceAgentEvent.State(DeviceAgentState.OBSERVING, "Public state")
        view.append(event); view.append(event)
        assertEquals(1, view.childCount)
        repeat(DeviceAgentLimits.MAX_EVENT_HISTORY + 3) { view.append(DeviceAgentEvent.ActionFinished(DeviceActionResult("call-$it", DeviceActionResultStatus.SUCCESS, "Public result"))) }
        assertEquals(DeviceAgentLimits.MAX_EVENT_HISTORY, view.childCount)
        view.clear()
        assertEquals(0, view.childCount)
    }

    @Test fun replayAndPendingDeliveryOfSameOutcomeProduceOneRow() {
        val view = DeviceTaskTimelineView(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ZeroInput))
        for (event in listOf(DeviceAgentEvent.Failed("Public failure"), DeviceAgentEvent.Completed("Public result"))) {
            view.clear()
            view.append(event); view.append(event)
            assertEquals(1, view.childCount)
        }
    }
}
