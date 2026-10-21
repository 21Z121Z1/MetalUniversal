package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;

final class MetalSubmissionBackpressureTest {
    @Test
    void boundsOutstandingWorkAcrossRepeatedStorageRingWraps() {
        for (int limit = 1; limit <= 3; limit++) {
            var policy = new MetalSubmissionBackpressure(Integer.toString(limit));
            var outstanding = new ArrayDeque<Long>();
            for (long next = 3; next < 1000; next++) {
                long witness = policy.completionRequiredBefore(next);
                while (!outstanding.isEmpty() && outstanding.getFirst() <= witness) outstanding.removeFirst();
                outstanding.addLast(next);
                assertTrue(outstanding.size() <= limit, "GPU submission budget exceeded");
                assertEquals(next - limit, witness);
                assertTrue(next - witness <= MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT,
                        "a wait may not refer to a submission whose three-slot storage has already retired");
            }
        }
    }

    @Test
    void malformedConfigurationPreservesDefaultAndNumericValuesStayWithinStorageBudget() {
        for (String text : new String[]{null, "", "invalid", "99999999999999999"}) {
            assertEquals(3, new MetalSubmissionBackpressure(text).limit());
        }
        assertEquals(1, new MetalSubmissionBackpressure("0").limit());
        assertEquals(1, new MetalSubmissionBackpressure("-4").limit());
        assertEquals(3, new MetalSubmissionBackpressure("9").limit());
        assertEquals(2, new MetalSubmissionBackpressure("2").limit());
    }
}
