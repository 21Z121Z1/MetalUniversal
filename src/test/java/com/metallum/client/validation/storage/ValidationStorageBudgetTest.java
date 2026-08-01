package com.metallum.client.validation.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationStorageBudgetTest {
    @Test
    void budgetCountsFinalFileSizeAndRewritesDoNotDoubleCount() throws Exception {
        Path root = Files.createTempDirectory("render-contract-budget-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root, 32L);

        budget.writeBytes(root.resolve("capture.bin"), new byte[]{1, 2, 3, 4});
        assertEquals(4L, budget.artifactBytes());

        budget.writeBytes(root.resolve("capture.bin"), new byte[]{1, 2});
        assertEquals(2L, budget.artifactBytes());
        assertEquals(30L, budget.remainingBytes());
        assertFalse(budget.exceeded());
    }

    @Test
    void budgetFailureIsStructuredAndRejectsFurtherWrites() throws Exception {
        Path root = Files.createTempDirectory("render-contract-budget-failure-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root, 16L);

        assertThrows(
                ValidationStorageBudget.StorageBudgetExceededException.class,
                () -> budget.writeBytes(root.resolve("too-large.bin"), new byte[17])
        );
        assertTrue(budget.exceeded());
        assertTrue(budget.failureReason().contains("budget"));
        assertThrows(
                ValidationStorageBudget.StorageBudgetExceededException.class,
                () -> budget.writeBytes(root.resolve("second.bin"), new byte[]{1})
        );

        budget.writeCriticalString(root.resolve("run-state.json"), "{\"status\":\"failed\"}\n");
        assertTrue(Files.exists(root.resolve("run-state.json")));
    }

    @Test
    void artifactPathCannotEscapeValidationRoot() throws Exception {
        Path root = Files.createTempDirectory("render-contract-budget-path-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root, 1024L);

        assertThrows(
                IllegalArgumentException.class,
                () -> budget.writeBytes(root.resolve("..").resolve("outside.bin"), new byte[]{1})
        );
    }

    @Test
    void managedTemporaryRunsUseTheSmallerDefaultBudget() throws Exception {
        Path root = Files.createTempDirectory("metallum-render-contract-budget-default-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root);

        assertEquals(ValidationStorageBudget.DEFAULT_TEMP_MAX_BYTES, budget.maxBytes());
        assertEquals(ValidationStorageBudget.DEFAULT_TEMP_MAX_BYTES,
                ValidationStorageBudget.defaultMaxBytes(root));
        assertEquals(ValidationStorageBudget.DEFAULT_TEMP_MAX_BYTES,
                ValidationStorageBudget.defaultMaxBytes(root.resolve("render-contract")));
    }

    @Test
    void nonManagedRootsKeepThePersistentDefaultBudget() throws Exception {
        Path root = Files.createTempDirectory("render-contract-persistent-budget-default-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root);

        assertEquals(ValidationStorageBudget.DEFAULT_MAX_BYTES, budget.maxBytes());
        assertEquals(ValidationStorageBudget.DEFAULT_MAX_BYTES,
                ValidationStorageBudget.defaultMaxBytes(root));
    }

    @Test
    void explicitArtifactPropertyOverridesTheTemporaryDefault() throws Exception {
        String previous = System.getProperty("metallum.renderContract.maxArtifactBytes");
        Path root = Files.createTempDirectory("metallum-render-contract-budget-override-");
        try {
            System.setProperty("metallum.renderContract.maxArtifactBytes", "12345");
            assertEquals(12345L, ValidationStorageBudget.shared(root).maxBytes());
        } finally {
            restoreProperty("metallum.renderContract.maxArtifactBytes", previous);
        }
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
