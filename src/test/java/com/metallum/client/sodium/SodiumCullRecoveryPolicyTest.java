package com.metallum.client.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumCullRecoveryPolicyTest {
    @Test
    void restoresInvalidationOnlyWhenPendingTaskDisappearsBeforeConsume() {
        assertTrue(SodiumCullRecoveryPolicy.shouldRestoreInvalidation(true, false));

        assertFalse(SodiumCullRecoveryPolicy.shouldRestoreInvalidation(false, false));
        assertFalse(SodiumCullRecoveryPolicy.shouldRestoreInvalidation(false, true));
        assertFalse(SodiumCullRecoveryPolicy.shouldRestoreInvalidation(true, true));
    }
}
