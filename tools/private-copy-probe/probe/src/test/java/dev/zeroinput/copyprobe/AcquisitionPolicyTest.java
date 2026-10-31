package dev.zeroinput.copyprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AcquisitionPolicyTest {
    @Test public void exactUnicodeIncludesSpacesNewlinesLinksAndEmoji() {
        String text = "\r\n 中  English\t1.20 https://example.com/?a=1&b=2\n\u00a0\u3000🙂👨‍👩‍👧‍👦🇨🇳  ";
        assertArrayEquals(text.toCharArray(), TextGate.copy(text));
    }
    @Test public void rejectsEmptyAndOnlyWhitespaceWithoutTrimmingValidText() {
        assertNull(TextGate.copy(null)); assertNull(TextGate.copy("")); assertNull(TextGate.copy(" \t\n\u00a0\u3000"));
        assertArrayEquals("  a  ".toCharArray(), TextGate.copy("  a  "));
    }
    @Test public void rejectsMalformedSurrogatesAndNul() {
        for (String text : new String[] {"a\0b", "\ud800", "\udc00", "\ud800a", "a\udc00b"}) assertNull(TextGate.copy(text));
    }
    @Test public void boundsBeforeReadingCharacters() {
        CharSequence hostile = new CharSequence() {
            public int length() { return TextGate.MAX_UNITS + 1; }
            public char charAt(int index) { throw new AssertionError("must not read"); }
            public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        assertNull(TextGate.copy(hostile));
        assertEquals(TextGate.MAX_UNITS, TextGate.copy("x".repeat(TextGate.MAX_UNITS)).length);
    }
    @Test public void onlyOneFrameAndOneReviewCanConsumeGeneration() {
        SessionGate gate = new SessionGate(); long token = gate.begin(100);
        assertTrue(gate.complete(token, 200)); assertFalse(gate.complete(token, 201));
        assertTrue(gate.claim(token, 202)); assertFalse(gate.claim(token, 203));
        assertTrue(gate.reviewing(token, 204));
    }
    @Test public void cancellationMakesLateFramesAndResultsInvalid() {
        SessionGate gate = new SessionGate(); long token = gate.begin(100);
        gate.clear(); assertFalse(gate.complete(token, 101)); assertFalse(gate.claim(token, 101));
        long replacement = gate.begin(102);
        assertFalse(gate.complete(token, 103)); assertTrue(gate.complete(replacement, 104));
        assertFalse(gate.reviewing(token, 105));
    }
    @Test public void consentCaptureAndReviewDeadlinesCannotBeExtendedByCallbacks() {
        SessionGate gate = new SessionGate(); long token = gate.begin(1);
        assertFalse(gate.complete(token, 1 + SessionGate.CAPTURE_MS));
        token = gate.begin(20_000); assertTrue(gate.complete(token, 20_100));
        assertTrue(gate.claim(token, 20_200));
        assertFalse(gate.reviewing(token, 20_100 + SessionGate.DRAFT_MS));
        assertEquals(SessionGate.State.EMPTY, gate.state(20_100 + SessionGate.DRAFT_MS));
    }
    @Test public void mapsReverseDragThroughLetterboxingAndClampsToImage() {
        assertArrayEquals(new int[] {0, 0, 80, 60}, CropGeometry.bounds(70, 65, -30, -10, 30, 35, .5f, 80, 60));
        assertArrayEquals(new int[] {10, 20, 40, 60}, CropGeometry.bounds(50, 65, 35, 45, 30, 35, .5f, 80, 80));
    }
    @Test public void emptyOffImageAndNonFiniteCropsFailClosed() {
        assertNull(CropGeometry.bounds(2, 2, 2, 2, 0, 0, 1, 80, 60));
        assertNull(CropGeometry.bounds(-10, -10, -1, -1, 0, 0, 1, 80, 60));
        assertNull(CropGeometry.bounds(0, 0, 10, 10, 0, 0, 0, 80, 60));
        assertNull(CropGeometry.bounds(Float.NaN, 0, 10, 10, 0, 0, 1, 80, 60));
    }
}
