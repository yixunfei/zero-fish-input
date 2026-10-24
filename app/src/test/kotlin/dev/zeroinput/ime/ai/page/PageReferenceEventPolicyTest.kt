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

    @Test fun packageLessBoundsResizeDoesNotRevokeExternalPageReview() {
        assertFalse(PageReferenceService.shouldRevokeMetadata(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            eventPackage = null,
            windowChanges = AccessibilityEvent.WINDOWS_CHANGE_BOUNDS,
        ))
    }

    @Test fun sourceAndUnknownWindowChangesRevokeReview() {
        assertTrue(PageReferenceService.shouldRevokeMetadata(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            eventPackage = "com.example.source",
            windowChanges = AccessibilityEvent.WINDOWS_CHANGE_BOUNDS,
        ))
        assertTrue(PageReferenceService.shouldRevokeMetadata(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            eventPackage = null,
            windowChanges = AccessibilityEvent.WINDOWS_CHANGE_FOCUSED,
        ))
        assertTrue(PageReferenceService.shouldRevokeMetadata(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            eventPackage = "com.example.source",
            windowChanges = 0,
        ))
    }

    @Test fun packageLessUnknownWindowChangeRevokesReview() {
        assertTrue(PageReferenceService.shouldRevokeMetadata(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            eventPackage = null,
            windowChanges = 0,
        ))
    }

    @Test fun ordinaryContentFocusAndTextEventsCannotTriggerAnotherCapture() {
        for (eventType in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
            assertFalse(PageReferenceService.invalidatesReview(eventType))
        }
    }
}
