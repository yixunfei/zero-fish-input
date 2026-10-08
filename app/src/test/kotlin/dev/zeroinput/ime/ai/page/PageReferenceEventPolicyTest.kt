package dev.zeroinput.ime.ai.page

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test

class PageReferenceEventPolicyTest {
    @Test fun windowStateChangesRevokeReviewEvenWhenSourcePackageAndWindowIdAreReused() {
        // The policy deliberately does not trust matching package/window identifiers after navigation.
        assertTrue(PageReferenceService.invalidatesReview(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
    }

    @Test fun windowSetChangesRevokeReviewWithoutEnumeratingPlatformWindows() {
        assertTrue(PageReferenceService.invalidatesReview(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
    }

    @Test fun ordinaryContentFocusAndTextEventsCannotTriggerAnotherCapture() {
        for (eventType in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
            assertFalse(PageReferenceService.invalidatesReview(eventType))
        }
    }
}
