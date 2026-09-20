package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttachmentActionsTest {
    @Test
    void preservesInitialAndFinalActionsWithoutClaimingBytesOrCompleteness() {
        long[] row = new long[40];
        row[0] = 7; row[1] = 9; row[2] = 11; row[3] = 4; row[4] = 13;
        row[5] = 0; row[6] = 2; row[7] = 70; row[8] = 1024; row[9] = 512;
        row[12] = 2; row[13] = 1; row[14] = 2; row[15] = 3;
        row[21] = 2; row[22] = 4; row[23] = 0; row[37] = 1; row[38] = 1;
        var snapshot = NativeAttachmentActions.decode(new long[]{1, 1, 64, 0, 1, 0, 1, 1}, row);
        var fact = snapshot.rows().getFirst();
        assertEquals(7, fact.windowId());
        assertEquals(13, fact.encoderSequence());
        assertEquals(2, fact.slot());
        assertEquals(3, fact.level());
        assertEquals(4, fact.initialStoreAction());
        assertEquals(0, fact.finalStoreAction());
        assertEquals(1, fact.errorBits());
        assertEquals(1, snapshot.invalidEvents(), "a valid ABI envelope is not complete measurement evidence");
        row[23] = 1;
        assertEquals(0, fact.finalStoreAction(), "decoded snapshot must own its immutable facts");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().clear());
    }

    @Test
    void rejectsMalformedNativeEnvelopeBeforeAllocatingRows() {
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[7], new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[]{2, 0, 0, 0, 0, 0, 0, 0}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[]{1, 2, 0, 0, 0, 0, 0, 0}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[]{1, 1, 65_537, 0, 0, 0, 0, 0}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[]{1, 1, 64, 0, 0, 0, 0, Long.MAX_VALUE}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[]{1, 1, 64, 0, -1, 0, 0, 0}, new long[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(new long[]{1, 1, 64, 0, 0, 0, 1, 1}, new long[39]));
    }

    @Test
    void encoderMarkerSupportsNoAttachmentCoverageButOtherNegativeWordsFailClosed() {
        long[] words = new long[40]; words[5] = -1;
        long[] header = {1, 1, 64, 0, 0, 1, 1, 1};
        var snapshot = NativeAttachmentActions.decode(header, words);
        assertEquals(-1, snapshot.rows().getFirst().aspect());
        assertEquals(1, snapshot.activeRenderEncoders());
        words[8] = -1;
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(header, words));
        words[8] = 0; words[5] = -2;
        assertThrows(IllegalArgumentException.class, () -> NativeAttachmentActions.decode(header, words));
    }
}
