package dev.zeroinput.ime.clipboardguard

import org.junit.Assert.*
import org.junit.Test

class OwnEmptyClipboardStampTest {
    @Test fun replacementAfterWriteIsNeverCapturedAsOwnEmptyItem() {
        val stamp = OwnEmptyClipboardStamp()
        stamp.record(103, 100, 102, 500)
        assertFalse(stamp.hides(103, 501))
        assertFalse(stamp.hides(103, 502))
    }

    @Test fun changedTimestampRevokesSuppressionIncludingAnOlderTimestampReturning() {
        val stamp = OwnEmptyClipboardStamp()
        stamp.record(101, 100, 102, 500)
        assertTrue(stamp.hides(101, 501))
        assertFalse(stamp.hides(103, 502))
        assertFalse(stamp.hides(101, 503))
    }

    @Test fun indistinguishableSameMillisecondReplacementCannotBeHiddenIndefinitely() {
        val stamp = OwnEmptyClipboardStamp()
        stamp.record(101, 100, 102, 500)
        assertTrue(stamp.hides(101, 1499))
        assertFalse(stamp.hides(101, 1500))
        assertFalse(stamp.hides(101, 1501))
    }

    @Test fun clockRollbackAndMissingMetadataNeverSuppress() {
        val stamp = OwnEmptyClipboardStamp()
        stamp.record(100, 102, 100, 500)
        assertFalse(stamp.hides(100, 501))
        stamp.record(null, 100, 102, 500)
        assertFalse(stamp.hides(null, 501))
    }
}
